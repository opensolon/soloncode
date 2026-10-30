package org.noear.solon.codecli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宿主边界护栏：soloncode-cli 不得再直接调用 ReActAgent/HarnessEngine 的 prompt 方法发起请求，
 * 一切输入必须经 {@code engine.handle(HarnessInput)} 装配。
 *
 * <p>白名单只允许两类豁免：</p>
 * <ul>
 *   <li>ChatModel 连通性探测（{@code chatModel.prompt("hi")}）——无会话/工具/命令语义，
 *       走 handle 会拉起主代理全链路，语义错误</li>
 *   <li>本测试自身</li>
 * </ul>
 *
 * @author noear 2026/10/30 created
 */
@DisplayName("宿主边界：宿主不再调用 prompt，一律 engine.handle")
class HostPromptBoundaryTest {

    /** 接收者为 engine / agentRuntime / agent / targetAgent 等代理句柄的 prompt 调用 */
    private static final Pattern AGENT_PROMPT_CALL = Pattern.compile(
            "\\b\\w*[eE]ngine\\w*\\.prompt\\s*\\(|\\bagentRuntime\\.prompt\\s*\\(|\\b\\w+[aA]gent\\w*\\.prompt\\s*\\(");

    private static final List<String> WHITELIST = Arrays.asList(
            // ChatModel 连通性探测：模型设置页 / 桌面模型页，非代理链路
            "api/web/settings/LlmSettingController.java",
            "api/desktop/controller/DesktopModelController.java",
            // 本测试
            "HostPromptBoundaryTest.java"
    );

    @Test
    @DisplayName("宿主源码中不存在 agent/engine 句柄的 prompt 调用")
    void hostsMustNotCallAgentPrompt() throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"), "src/main/java");

        assertTrue(Files.isDirectory(root), "找不到源码目录: " + root);

        Map<String, Integer> violations = new HashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        return WHITELIST.stream().noneMatch(rel::endsWith);
                    })
                    .forEach(p -> {
                        try {
                            String code = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            Matcher m = AGENT_PROMPT_CALL.matcher(code);
                            if (m.find()) {
                                violations.put(root.relativize(p).toString(), findLine(code, m.start()));
                            }
                        } catch (IOException ignored) {
                        }
                    });
        }

        assertTrue(violations.isEmpty(),
                "宿主不得直接调用 agent/engine 的 prompt()（应走 engine.handle）：\n" +
                        violations.entrySet().stream()
                                .map(e -> "  " + e.getKey() + ":" + e.getValue())
                                .reduce((a, b) -> a + "\n" + b).orElse(""));
    }

    private static int findLine(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
