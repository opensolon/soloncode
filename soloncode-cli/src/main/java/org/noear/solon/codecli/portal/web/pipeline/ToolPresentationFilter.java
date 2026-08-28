package org.noear.solon.codecli.portal.web.pipeline;

import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.cli.TodoTalent;
import org.noear.solon.ai.talents.lsp.LspCheckState;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.ToolEndPayload;
import org.noear.solon.codecli.portal.web.event.payload.ToolLspDiagnostic;
import org.noear.solon.codecli.portal.web.event.payload.ToolLspInfo;
import org.noear.solon.core.util.Assert;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具输出专用格式化过滤器（处理 Git Diff 重建、参数瘦身、LSP 诊断结构化）
 *
 * @author noear
 */
public class ToolPresentationFilter {
    /**
     * 诊断块：与 LspDiagnosticReporter.renderBlock 的输出格式对应。
     * 只在此一处做文本解析，解析结果以结构化字段下发，前端不再碰文本。
     */
    private static final Pattern LSP_BLOCK = Pattern.compile(
            "<diagnostics file=\"([^\"]*)\">\\s*\\n(.*?)\\n?</diagnostics>", Pattern.DOTALL);

    private static final Pattern LSP_ITEM = Pattern.compile("^ERROR \\[(\\d+):(\\d+)\\]\\s*(.*)$");

    private static final Pattern LSP_MORE = Pattern.compile("^\\.\\.\\. and (\\d+) more$");

    /**
     * 诊断注入的祈使句前缀（LspDiagnosticReporter.PROMPT_PREFIX）：
     * 剥离展示文本时以它为切点，避免把给模型的措辞显示给用户。
     */
    private static final String LSP_PROMPT_PREFIX = "LSP errors detected in this file";

    /**
     * 查询某文件最近一次写入的 LSP 检查状态。
     *
     * <p>必须是三态而非「有没有语言服务器」的布尔判定：语言服务器冷启动时诊断等待会超时，
     * 此时既没有诊断也没有结论，若与真正的无错误合并展示，就会给出比实际更强的保证。
     * 为 null 时只上报有错误的场景。
     */
    private final Function<String, LspCheckState> lspState;

    public ToolPresentationFilter() {
        this(null);
    }

    public ToolPresentationFilter(Function<String, LspCheckState> lspState) {
        this.lspState = lspState;
    }

    public WebEvent<?> apply(WebEvent<?> event) {
        if (event == null || !WebEventNames.TOOL_END.equals(event.getEvent())) {
            return event;
        }

        if (!(event.getPayload() instanceof ToolEndPayload)) {
            return event;
        }

        ToolEndPayload payload = (ToolEndPayload) event.getPayload();
        Map<String, Object> args = payload.getArgs();

        //必须在 write 用 args.content 覆盖 result 之前抽取，否则诊断文本会被覆盖丢失
        fillLspInfo(payload, args);

        if (args != null) {
            // 如果 args 是不可变 Map（如 Collections.unmodifiableMap），包装为可变 HashMap 避免修改报错
            if (!(args instanceof HashMap)) {
                args = new HashMap<>(args);
                payload.setArgs(args);
            }

            if (TodoTalent.TOOL_TODOWRITE.equals(payload.getName())) {
                String todos = (String) args.get(TodoTalent.PARAM_TODOS);
                if (Assert.isNotEmpty(todos)) {
                    payload.setResult(todos);
                    args.remove(TodoTalent.PARAM_TODOS);
                }
            }

            if (TerminalTalent.TOOL_WRITE.equals(payload.getName())) {
                String content = (String) args.get(TerminalTalent.PARAM_CONTENT);
                if (Assert.isNotEmpty(content)) {
                    payload.setResult(content);
                    args.remove(TerminalTalent.PARAM_CONTENT);
                }
            }

            fillEditDiff(payload, args);
        }

        return event;
    }

    /**
     * 从工具输出中抽取 LSP 诊断为结构化字段，并把诊断文本从展示结果中剥离。
     *
     * <p>只处理会触发写入诊断钩子的 write/edit；解析失败一律降级为不上报，
     * 绝不能让展示层的加工影响工具结果本身。
     */
    private void fillLspInfo(ToolEndPayload payload, Map<String, Object> args) {
        String toolName = payload.getName();
        if (TerminalTalent.TOOL_WRITE.equals(toolName) == false
                && TerminalTalent.TOOL_EDIT.equals(toolName) == false) {
            return;
        }

        try {
            String result = payload.getResult();
            String filePath = (args == null) ? null : asString(args.get("file_path"));

            if (Assert.isNotEmpty(result)) {
                Matcher m = LSP_BLOCK.matcher(result);
                if (m.find()) {
                    payload.setLsp(parseLspBlock(m.group(1), m.group(2), filePath));
                    payload.setResult(stripLspText(result, m.start()));
                    return;
                }
            }

            //无诊断块：区分「已检查且无错误」与「已请求但未拿到结论」，后者不能声称文件干净
            if (lspState == null || Assert.isEmpty(filePath)) {
                return;
            }

            LspCheckState state = lspState.apply(filePath);
            if (state == LspCheckState.CLEAN || state == LspCheckState.PENDING) {
                payload.setLsp(ToolLspInfo.builder()
                        .file(filePath)
                        .errorCount(0)
                        .truncated(false)
                        .items(Collections.emptyList())
                        .pending(state == LspCheckState.PENDING)
                        .build());
            }
        } catch (Throwable e) {
            //展示层加工失败不影响工具结果
            payload.setLsp(null);
        }
    }

    private ToolLspInfo parseLspBlock(String blockFile, String body, String fallbackFile) {
        List<ToolLspDiagnostic> items = new ArrayList<>();
        int truncatedCount = 0;

        for (String raw : body.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher more = LSP_MORE.matcher(line);
            if (more.matches()) {
                truncatedCount = asInt(more.group(1), 0);
                continue;
            }

            Matcher item = LSP_ITEM.matcher(line);
            if (item.matches() == false) {
                continue;
            }

            String message = item.group(3).trim();
            String source = null;
            //渲染层把来源写在行尾的括号里：ERROR [12:5] message (javac)
            int open = message.lastIndexOf(" (");
            if (open > 0 && message.endsWith(")")) {
                String candidate = message.substring(open + 2, message.length() - 1);
                if (candidate.indexOf('(') < 0 && candidate.indexOf(')') < 0) {
                    source = candidate;
                    message = message.substring(0, open).trim();
                }
            }

            items.add(ToolLspDiagnostic.builder()
                    .line(asInt(item.group(1), 0))
                    .column(asInt(item.group(2), 0))
                    .message(message)
                    .source(source)
                    .build());
        }

        if (items.isEmpty() && truncatedCount == 0) {
            return null;
        }

        String file = Assert.isNotEmpty(blockFile) ? blockFile : fallbackFile;
        return ToolLspInfo.builder()
                .file(file)
                .errorCount(items.size() + truncatedCount)
                .truncated(truncatedCount > 0)
                .items(items)
                .build();
    }

    /**
     * 剥离展示结果里的诊断文本：连同它前面的祈使句与空行一起去掉
     */
    private String stripLspText(String result, int blockStart) {
        int cut = blockStart;
        int prefixAt = result.lastIndexOf(LSP_PROMPT_PREFIX, blockStart);
        if (prefixAt >= 0) {
            cut = prefixAt;
        }
        String head = result.substring(0, cut);
        //去掉尾部空白，但保留原本的结果文本（如"文件成功写入: xxx"）
        return head.replaceAll("\\s+$", "");
    }

    @SuppressWarnings("unchecked")
    private void fillEditDiff(ToolEndPayload payload, Map<String, Object> args) {
        if (args == null || !(args.get(TerminalTalent.PARAM_EDITS) instanceof List)) {
            return;
        }

        List<?> edits = (List<?>) args.get(TerminalTalent.PARAM_EDITS);
        if (edits.isEmpty()) {
            return;
        }

        StringBuilder diff = new StringBuilder();
        for (Object item : edits) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> edit = (Map<String, Object>) item;

            int startLine = asInt(edit.get("old_StrStartLine"), 0);
            List<String> oldLines = splitLines(asString(edit.get("old_str")));
            List<String> newLines = splitLines(asString(edit.get("new_str")));

            diff.append("@@ -").append(startLine).append(',').append(oldLines.size())
                    .append(" +").append(startLine).append(',').append(newLines.size())
                    .append(" @@\n");

            for (String line : oldLines) {
                diff.append('-').append(line).append('\n');
            }
            for (String line : newLines) {
                diff.append('+').append(line).append('\n');
            }
        }

        if (diff.length() > 0) {
            String diffStr = diff.toString();
            payload.setDiff(diffStr);
            args.put("diff", diffStr);
            args.remove(TerminalTalent.PARAM_EDITS);
        }
    }

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int asInt(Object o, int fallback) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        String[] raw = text.split("\r?\n", -1);
        List<String> list = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            if (i == raw.length - 1 && raw[i].isEmpty()) {
                break;
            }
            list.add(raw[i]);
        }
        return list;
    }
}
