package org.noear.solon.codecli.portal.web.settings;

import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Web 设置：子代理 Markdown 文件管理。
 *
 * @author noear
 * @since 2026.7
 */
public class AgentSettingsController extends BaseSettingsController {
    static final String USER_ALIAS = "@user-agents";
    static final String WORKSPACE_ALIAS = "@workspace-agents";
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MAX_PROMPT_LENGTH = 256 * 1024;
    private static final int MAX_FILE_SIZE = 512 * 1024;
    private static final String BUILTIN_RESOURCE_BASE = "META-INF/solon/ai/harness/";
    private static final String[] BUILTIN_NAMES = {"general", "explore", "bash", "plan", "git-summary"};
    private static final Pattern TOP_LEVEL_KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]*\\s*:.*$");
    private static final Logger LOG = LoggerFactory.getLogger(AgentSettingsController.class);

    public AgentSettingsController(HarnessEngine engine, AgentSettings settings,
                                   FileWatchService fileWatchService, WebGate webGate) {
        super(engine, settings, fileWatchService, webGate);
    }

    @Get
    @Mapping("/web/settings/agents")
    public Result agentsList() {
        Map<String, AgentDefinition> builtinMap = loadBuiltinDefinitions();
        Map<String, Map<String, Object>> candidatesByName = new LinkedHashMap<>();
        for (AgentDefinition definition : builtinMap.values()) {
            Map<String, Object> item = toItem(definition, AgentFlags.SCOPE_USER, true);
            item.put("sourceScope", "builtin");
            item.put("editable", true);
            candidatesByName.put(definition.getName(), item);
        }
        addFileAgents(candidatesByName, AgentFlags.SCOPE_USER, userRoot(), builtinMap);
        addFileAgents(candidatesByName, AgentFlags.SCOPE_LOCAL, workspaceRoot(), builtinMap);

        List<Map<String, Object>> list = new ArrayList<>(candidatesByName.values());
        Collections.sort(list, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name")));
            }
        });
        return Result.succeed(list);
    }

    @Get
    @Mapping("/web/settings/agents/get")
    public Result agentsGet(@Param("name") String name, @Param("scope") String scope) {
        String invalid = validateName(name);
        if (invalid != null) return Result.failure(invalid);

        try {
            if ("builtin".equals(scope)) {
                AgentDefinition definition = findBuiltin(name);
                if (definition == null) return Result.failure("内置智能体不存在: " + name);
                Map<String, Object> data = toItem(definition, "builtin", true);
                data.put("systemPrompt", definition.getSystemPrompt());
                data.put("editable", true);
                return Result.succeed(data);
            }

            Path file = resolveFile(scope, name);
            if (!Files.exists(file) || Files.isDirectory(file)) return Result.failure("智能体不存在: " + name);
            if (Files.isSymbolicLink(file)) return Result.failure("不允许读取符号链接文件");
            if (Files.size(file) > MAX_FILE_SIZE) return Result.failure("智能体文件过大，最大允许 512 KB");
            String markdown = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            try {
                AgentDefinition definition = parseAndValidate(markdown, name);
                Map<String, Object> data = toItem(definition, normalizeScope(scope), false);
                data.put("systemPrompt", definition.getSystemPrompt());
                data.put("editable", true);
                return Result.succeed(data);
            } catch (IllegalArgumentException e) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("name", name);
                data.put("description", "");
                data.put("tools", Collections.emptyList());
                data.put("systemPrompt", extractSystemPrompt(markdown));
                data.put("scope", normalizeScope(scope));
                data.put("editable", true);
                data.put("valid", false);
                data.put("parseError", e.getMessage());
                return Result.succeed(data);
            }
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to read agent {}: {}", name, e.getMessage());
            return Result.failure("读取失败: " + e.getMessage());
        }
    }

    @Post
    @Mapping("/web/settings/agents/add")
    public Result agentsAdd(@Body String json) {
        return saveAgent(json, false);
    }

    @Post
    @Mapping("/web/settings/agents/update")
    public Result agentsUpdate(@Body String json) {
        return saveAgent(json, true);
    }

    @Post
    @Mapping("/web/settings/agents/remove")
    public Result agentsRemove(@Body String json) {
        try {
            ONode root = ONode.ofJson(json);
            String name = root.get("name").getString();
            String scope = normalizeScope(root.get("scope").getString());
            String invalid = validateName(name);
            if (invalid != null) return Result.failure(invalid);

            Path file = resolveFile(scope, name);
            if (!Files.exists(file)) return Result.failure("智能体不存在: " + name);
            if (Files.isDirectory(file) || Files.isSymbolicLink(file)) return Result.failure("拒绝删除非普通智能体文件");
            Files.delete(file);
            refresh(scope);
            return Result.succeed("删除成功");
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to remove agent: {}", e.getMessage());
            return Result.failure("删除失败: " + e.getMessage());
        }
    }

    @Post
    @Mapping("/web/settings/agents/refresh")
    public Result agentsRefresh() {
        try {
            engine.getAgentManager().refreshByMountAlias(USER_ALIAS);
            engine.getAgentManager().refreshByMountAlias(WORKSPACE_ALIAS);
            return Result.succeed("刷新成功");
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to refresh agents: {}", e.getMessage());
            return Result.failure("刷新失败: " + e.getMessage());
        }
    }

    private Result saveAgent(String json, boolean update) {
        Path temp = null;
        try {
            ONode root = ONode.ofJson(json);
            String scope = normalizeScope(root.get("scope").getString());
            String name = root.get("name").getString();
            String description = root.get("description").getString();
            String systemPrompt = root.get("systemPrompt").getString();
            List<String> tools = readStringList(root.get("tools"));
            String originalName = root.get("originalName").getString();
            String originalScopeValue = root.get("originalScope").getString();
            String originalScope = Assert.isEmpty(originalScopeValue) ? scope : normalizeScope(originalScopeValue);

            String invalid = validateName(name);
            if (invalid != null) return Result.failure(invalid);
            if (Assert.isEmpty(description)) return Result.failure("描述不能为空");
            if (Assert.isEmpty(systemPrompt)) return Result.failure("系统提示词不能为空");
            if (description.length() > MAX_DESCRIPTION_LENGTH) return Result.failure("描述最长 1000 个字符");
            if (systemPrompt.length() > MAX_PROMPT_LENGTH) return Result.failure("系统提示词最大允许 256 KB");

            String originalMarkdown = null;
            if (update) {
                if (Assert.isEmpty(originalName)) originalName = name;
                invalid = validateName(originalName);
                if (invalid != null) return Result.failure(invalid);
                Path original = resolveFile(originalScope, originalName);
                if (!Files.exists(original) || Files.isDirectory(original) || Files.isSymbolicLink(original)) {
                    return Result.failure("原智能体不存在或不可编辑: " + originalName);
                }
                if (Files.size(original) > MAX_FILE_SIZE) return Result.failure("原智能体文件过大，无法通过表单编辑");
                originalMarkdown = new String(Files.readAllBytes(original), StandardCharsets.UTF_8);
            } else {
                String sourceName = root.get("sourceName").getString();
                String sourceScope = root.get("sourceScope").getString();
                if (!Assert.isEmpty(sourceName) && !Assert.isEmpty(sourceScope)) {
                    String sourceInvalid = validateName(sourceName);
                    if (sourceInvalid != null) return Result.failure(sourceInvalid);
                    if ("builtin".equals(sourceScope)) {
                        originalMarkdown = loadBuiltinMarkdown(sourceName);
                    } else {
                        Path source = resolveFile(sourceScope, sourceName);
                        if (Files.isRegularFile(source) && !Files.isSymbolicLink(source) && Files.size(source) <= MAX_FILE_SIZE) {
                            originalMarkdown = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                        }
                    }
                }
            }

            String markdown = buildMarkdown(name, description, tools, systemPrompt, originalMarkdown);
            if (markdown.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_SIZE) return Result.failure("智能体文件最大允许 512 KB");
            parseAndValidate(markdown, name);

            Path target = resolveFile(scope, name);
            if (Files.isSymbolicLink(target)) return Result.failure("不允许覆盖符号链接文件");

            if (!update && Files.exists(target)) return Result.failure("同作用域智能体已存在: " + name);
            if (update) {
                Path original = resolveFile(originalScope, originalName);
                if (!Files.exists(original) || Files.isDirectory(original) || Files.isSymbolicLink(original)) {
                    return Result.failure("原智能体不存在或不可编辑: " + originalName);
                }
                if (!original.equals(target) && Files.exists(target)) return Result.failure("目标智能体已存在: " + name);
            }

            Path targetRoot = target.getParent();
            ensureWritableRoot(targetRoot);
            temp = Files.createTempFile(targetRoot, ".agent-", ".tmp");
            Files.write(temp, markdown.getBytes(StandardCharsets.UTF_8));
            moveReplace(temp, target);
            temp = null;

            if (update) {
                Path original = resolveFile(originalScope, Assert.isEmpty(originalName) ? name : originalName);
                if (!original.equals(target)) Files.deleteIfExists(original);
            }

            refresh(scope);
            if (update && !scope.equals(originalScope)) refresh(originalScope);
            return Result.succeed(update ? "更新成功" : "添加成功");
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to save agent: {}", e.getMessage());
            return Result.failure("保存失败: " + e.getMessage());
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (Exception ignored) { }
            }
        }
    }

    private void addFileAgents(Map<String, Map<String, Object>> list, String scope, Path root,
                               Map<String, AgentDefinition> builtinMap) {
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.md")) {
            for (Path file : stream) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) continue;
                String fileName = file.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - 3);
                Map<String, Object> item;
                try {
                    if (Files.size(file) > MAX_FILE_SIZE) throw new IllegalArgumentException("文件超过 512 KB");
                    String markdown = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    AgentDefinition definition = parseAndValidate(markdown, name);
                    item = toItem(definition, scope, false);
                    item.put("sourceScope", "builtin");
                    item.put("editable", true);
                } catch (Exception e) {
                    item = new LinkedHashMap<>();
                    item.put("name", name);
                    item.put("description", e.getMessage());
                    item.put("scope", scope);
                    item.put("builtin", false);
                    item.put("editable", true);
                    item.put("valid", false);
                }
                boolean workspaceExists = hasAgentFile(workspaceRoot(), name);
                boolean userExists = hasAgentFile(userRoot(), name);
                if (AgentFlags.SCOPE_USER.equals(scope)) {
                    item.put("overridden", workspaceExists);
                    item.put("overriddenBy", workspaceExists ? AgentFlags.SCOPE_LOCAL : null);
                    item.put("effective", !workspaceExists);
                } else {
                    item.put("overridesUser", userExists);
                    item.put("effective", true);
                }
                item.put("fileName", fileName);
                list.put(name, item);
            }
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to scan agent root {}: {}", root, e.getMessage());
        }
    }

    private Map<String, Object> toItem(AgentDefinition definition, String scope, boolean builtin) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", definition.getName());
        item.put("description", definition.getDescription());
        item.put("model", definition.getModel());
        item.put("tools", definition.getMetadata().getTools());
        item.put("disallowedTools", definition.getMetadata().getDisallowedTools());
        item.put("enabled", definition.getMetadata().isEnabled());
        item.put("hidden", definition.getMetadata().isHidden());
        item.put("scope", scope);
        item.put("builtin", builtin);
        item.put("valid", true);
        return item;
    }

    private AgentDefinition findBuiltin(String name) {
        return loadBuiltinDefinitions().get(name);
    }

    private String loadBuiltinMarkdown(String name) throws Exception {
        try (InputStream input = AgentSettingsController.class.getClassLoader()
                .getResourceAsStream(BUILTIN_RESOURCE_BASE + name + ".md")) {
            if (input == null) throw new IllegalArgumentException("内置智能体不存在: " + name);
            return readUtf8(input);
        }
    }

    private Map<String, AgentDefinition> loadBuiltinDefinitions() {
        Map<String, AgentDefinition> result = new LinkedHashMap<>();
        for (String name : BUILTIN_NAMES) {
            try {
                String markdown = loadBuiltinMarkdown(name);
                AgentDefinition definition = parseAndValidate(markdown, name);
                result.put(name, definition);
            } catch (Exception e) {
                LOG.debug("[Settings] Failed to load builtin agent {}: {}", name, e.getMessage());
            }
        }
        return result;
    }

    private List<String> readStringList(ONode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (ONode item : node.getArray()) {
                String value = item.getString();
                if (!Assert.isEmpty(value) && !values.contains(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private String buildMarkdown(String name, String description, List<String> tools,
                                 String systemPrompt, String originalMarkdown) {
        List<String> preservedLines = extractPreservedFrontMatter(originalMarkdown);
        StringBuilder markdown = new StringBuilder();
        markdown.append("---\n");
        markdown.append("name: ").append(ONode.ofBean(name).toJson()).append('\n');
        markdown.append("description: ").append(ONode.ofBean(description).toJson()).append('\n');
        markdown.append("tools: ").append(ONode.ofBean(tools).toJson()).append('\n');
        for (String line : preservedLines) {
            markdown.append(line).append('\n');
        }
        markdown.append("---\n\n");
        markdown.append(systemPrompt.trim()).append('\n');
        return markdown.toString();
    }

    private List<String> extractPreservedFrontMatter(String markdown) {
        List<String> result = new ArrayList<>();
        if (Assert.isEmpty(markdown)) return result;
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0].trim())) return result;
        boolean skip = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if ("---".equals(line.trim())) break;
            boolean topLevel = !line.isEmpty() && !Character.isWhitespace(line.charAt(0))
                    && TOP_LEVEL_KEY_PATTERN.matcher(line).matches();
            if (topLevel) {
                String key = line.substring(0, line.indexOf(':')).trim();
                skip = "name".equals(key) || "description".equals(key) || "tools".equals(key);
            }
            if (!skip) result.add(line);
        }
        while (!result.isEmpty() && result.get(result.size() - 1).trim().isEmpty()) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private String extractSystemPrompt(String markdown) {
        if (markdown == null) return "";
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        int first = normalized.indexOf("---");
        if (first != 0) return normalized.trim();
        int second = normalized.indexOf("\n---", 3);
        if (second < 0) return "";
        int start = normalized.indexOf('\n', second + 1);
        return start < 0 ? "" : normalized.substring(start + 1).trim();
    }

    private String readUtf8(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = input.read(buffer)) >= 0) {
            output.write(buffer, 0, len);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private AgentDefinition parseAndValidate(String markdown, String expectedName) {
        AgentDefinition definition;
        try {
            definition = AgentDefinition.fromMarkdown(markdown);
        } catch (Exception e) {
            throw new IllegalArgumentException("Markdown/YAML 格式无效: " + e.getMessage());
        }
        String name = definition.getName();
        String invalid = validateName(name);
        if (invalid != null) throw new IllegalArgumentException(invalid);
        if (!expectedName.equals(name)) throw new IllegalArgumentException("文件名与 name 字段必须一致");
        return definition;
    }

    private String validateName(String name) {
        if (Assert.isEmpty(name)) return "name 为必填项";
        if (!NAME_PATTERN.matcher(name).matches()) return "名称仅允许字母、数字、下划线和连字符，且最长 64 个字符";
        return null;
    }

    private String normalizeScope(String scope) {
        if (AgentFlags.SCOPE_USER.equals(scope)) return AgentFlags.SCOPE_USER;
        if (AgentFlags.SCOPE_LOCAL.equals(scope)) return AgentFlags.SCOPE_LOCAL;
        throw new IllegalArgumentException("无效作用域");
    }

    private Path userRoot() {
        return Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessAgents()).toAbsolutePath().normalize();
    }

    private Path workspaceRoot() {
        return Paths.get(engine.getWorkspace(), AgentFlags.getHarnessAgents()).toAbsolutePath().normalize();
    }

    private Path resolveFile(String scope, String name) {
        String normalizedScope = normalizeScope(scope);
        Path root = AgentFlags.SCOPE_USER.equals(normalizedScope) ? userRoot() : workspaceRoot();
        Path target = root.resolve(name + ".md").normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("非法路径");
        return target;
    }

    private void ensureWritableRoot(Path root) throws Exception {
        if (Files.exists(root)) {
            if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
                throw new IllegalArgumentException("智能体目录不是安全的普通目录");
            }
        } else {
            Files.createDirectories(root);
        }
    }

    private boolean hasAgentFile(Path root, String name) {
        if (name == null) return false;
        Path target = root.resolve(name + ".md").normalize();
        return target.startsWith(root) && Files.isRegularFile(target) && !Files.isSymbolicLink(target);
    }

    private void refresh(String scope) {
        engine.getAgentManager().refreshByMountAlias(AgentFlags.SCOPE_USER.equals(scope) ? USER_ALIAS : WORKSPACE_ALIAS);
    }

    private void moveReplace(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int scopeOrder(String scope) {
        if (AgentFlags.SCOPE_LOCAL.equals(scope)) return 0;
        if (AgentFlags.SCOPE_USER.equals(scope)) return 1;
        return 2;
    }
}
