package org.noear.solon.codecli.portal.web.run;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * /web/run 请求体规范化：JSON → CLI argv + 执行环境
 *
 * <p>按 run-headless-mode-http.md：options 字段与 CLI flag 一一对应（snake_case），
 * 未识别字段拒绝（400）。permission-mode 只能收紧不能放宽：服务端上限默认
 * {@code dontAsk}，请求 {@code bypassPermissions} 直接拒绝（403）。workspace 只能取
 * 服务端已注册工作区（含默认），不接受任意路径。</p>
 *
 * <p>执行模型：每请求子进程（{@code java -cp ... App run ...}，cwd=工作区路径）。
 * 不在 web 进程内复用 PrintMode——它会改写 engine 的权限规则/挂载/MCP，
 * 而 web 默认 engine 与交互式会话共享，进程内执行会污染交互会话；
 * 子进程与 CLI 用户走完全相同的解析与执行路径，会话仓库（按 cwd 定位）天然互通，
 * HTTP 客户端与本机 CLI 可互相续接同一会话。</p>
 *
 * @author noear 2026/8/28 created
 */
public class RunRequestService {
    private static final Logger LOG = LoggerFactory.getLogger(RunRequestService.class);

    /** options 里合法的 snake_case 字段 */
    private static final Set<String> KNOWN_OPTION_FIELDS = new HashSet<>(Arrays.asList(
            "output_format", "model", "max_turns", "session_id", "resume", "continue",
            "allowed_tools", "disallowed_tools", "permission_mode", "fallback_model",
            "json_schema", "max_budget_usd", "bare", "add_dirs"
    ));

    /** 允许的 permission_mode 值（除 bypassPermissions 的收口见 #apply） */
    private static final Set<String> KNOWN_PERMISSION_MODES = new HashSet<>(Arrays.asList(
            "default", "acceptEdits", "plan", "dontAsk", "bypassPermissions"
    ));

    /** 允许的 output_format 值 */
    private static final Set<String> KNOWN_OUTPUT_FORMATS = new HashSet<>(Arrays.asList(
            "text", "json", "stream-json"
    ));

    /** permission-mode 服务端上限：bypassPermissions 永不放行（不提供放宽配置） */
    private static final String FORBIDDEN_PERMISSION_MODE = "bypassPermissions";

    private final WorkspaceManager workspaceManager;

    public RunRequestService(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /**
     * 工作区解析钩子（默认实现：默认工作区 + 历史清单；测试可覆写注入假清单）
     */
    protected List<WorkspaceMeta> listKnownWorkspaces() {
        List<WorkspaceMeta> all = new ArrayList<>();
        all.add(workspaceManager.getOrCreate(null).getMeta()); // 默认 launch
        all.addAll(workspaceManager.listWorkspaces());
        return all;
    }

    /**
     * 规范化结果：argv（不含 "run" 前缀）+ 工作目录
     */
    public static class NormalizedRequest {
        public final List<String> argv = new ArrayList<>();
        public String workspacePath;
        public String workspaceName;
        public String sessionId;      // 用于 409 锁定的会话标识（session_id/resume/continue）
        public ONode metadata;        // 透传字段（原样回显）
        public String prompt;
    }

    /**
     * 解析失败（400 类）
     */
    public static class BadRequestException extends Exception {
        public BadRequestException(String message) {
            super(message);
        }
    }

    /**
     * 权限收口（403 类）
     */
    public static class ForbiddenException extends Exception {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    /**
     * 工作区不存在（404 类）
     */
    public static class WorkspaceNotFoundException extends Exception {
        public WorkspaceNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 解析并规范化请求体
     *
     * @param bodyJson 请求体 JSON 根节点
     */
    public NormalizedRequest normalize(ONode bodyJson) throws BadRequestException,
            ForbiddenException, WorkspaceNotFoundException {
        if (bodyJson == null || !bodyJson.isObject()) {
            throw new BadRequestException("Request body must be a JSON object");
        }

        NormalizedRequest req = new NormalizedRequest();

        // ---- prompt（必填，纯文本）----
        String prompt = bodyJson.get("prompt").getString();
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new BadRequestException("'prompt' is required");
        }
        req.prompt = prompt;

        // ---- options（可选）----
        ONode options = bodyJson.get("options");
        if (options != null && !options.isNull()) {
            if (!options.isObject()) {
                throw new BadRequestException("'options' must be a JSON object");
            }
            applyOptions(req, options);
        }

        // ---- workspace（可选，白名单）----
        applyWorkspace(req, bodyJson.get("workspace"));

        // ---- metadata（可选，透传）----
        ONode metadata = bodyJson.get("metadata");
        if (metadata != null && !metadata.isNull()) {
            req.metadata = metadata;
        }

        // ---- 会话锁定标识 ----
        req.sessionId = resolveLockSessionId(req);

        return req;
    }

    /**
     * options → argv flag。未识别字段拒绝。
     */
    private void applyOptions(NormalizedRequest req, ONode options) throws BadRequestException,
            ForbiddenException {
        // 未识别字段检查（先于取值，契约：字段拼错的调用方必须尽早失败）
        for (String key : options.getObject().keySet()) {
            if (!KNOWN_OPTION_FIELDS.contains(key)) {
                throw new BadRequestException("Unknown option field: '" + key
                        + "'. See run-headless-mode-http.md for the full field list.");
            }
        }

        // output_format（校验枚举）
        ONode outputFormat = options.get("output_format");
        if (notNull(outputFormat)) {
            String val = outputFormat.getString();
            if (!KNOWN_OUTPUT_FORMATS.contains(val)) {
                throw new BadRequestException("Invalid output_format: " + val);
            }
            req.argv.add("--output-format=" + val);
        }

        // model
        ONode model = options.get("model");
        if (notNull(model)) {
            req.argv.add("--model=" + model.getString());
        }

        // max_turns（正整数）
        ONode maxTurns = options.get("max_turns");
        if (notNull(maxTurns)) {
            Integer val = maxTurns.getInt();
            if (val == null || val <= 0) {
                throw new BadRequestException("max_turns must be a positive integer");
            }
            req.argv.add("--max-turns=" + val);
        }

        // session_id
        ONode sessionId = options.get("session_id");
        if (notNull(sessionId)) {
            req.argv.add("--session-id=" + sessionId.getString());
        }

        // resume
        ONode resume = options.get("resume");
        if (notNull(resume)) {
            req.argv.add("--resume=" + resume.getString());
        }

        // continue（bool）
        ONode cont = options.get("continue");
        if (notNull(cont)) {
            if (!cont.isBoolean()) {
                throw new BadRequestException("continue must be a boolean");
            }
            if (cont.getBoolean()) {
                req.argv.add("--continue");
            }
        }

        // allowed_tools / disallowed_tools（数组，ToolName(pattern) 语法透传）
        appendToolList(req.argv, "--allowedTools=", options.get("allowed_tools"));
        appendToolList(req.argv, "--disallowedTools=", options.get("disallowed_tools"));

        // permission_mode（服务端收口）
        ONode permissionMode = options.get("permission_mode");
        if (notNull(permissionMode)) {
            String val = permissionMode.getString();
            if (!KNOWN_PERMISSION_MODES.contains(val)) {
                throw new BadRequestException("Invalid permission_mode: " + val);
            }
            if (FORBIDDEN_PERMISSION_MODE.equals(val)) {
                throw new ForbiddenException(
                        "permission_mode 'bypassPermissions' is not allowed over /web/run");
            }
            req.argv.add("--permission-mode=" + val);
        }

        // fallback_model
        ONode fallbackModel = options.get("fallback_model");
        if (notNull(fallbackModel)) {
            req.argv.add("--fallback-model=" + fallbackModel.getString());
        }

        // json_schema（object，序列化为字符串传给 --json-schema）
        ONode jsonSchema = options.get("json_schema");
        if (notNull(jsonSchema)) {
            if (!jsonSchema.isObject()) {
                throw new BadRequestException("json_schema must be a JSON object");
            }
            req.argv.add("--json-schema=" + jsonSchema.toJson());
        }

        // max_budget_usd（正数）
        ONode maxBudget = options.get("max_budget_usd");
        if (notNull(maxBudget)) {
            Double val = maxBudget.getDouble();
            if (val == null || val <= 0) {
                throw new BadRequestException("max_budget_usd must be a positive number");
            }
            req.argv.add("--max-budget-usd=" + val);
        }

        // bare（bool）
        ONode bare = options.get("bare");
        if (notNull(bare)) {
            if (!bare.isBoolean()) {
                throw new BadRequestException("bare must be a boolean");
            }
            if (bare.getBoolean()) {
                req.argv.add("--bare");
            }
        }

        // add_dirs（数组，可重复 flag）
        ONode addDirs = options.get("add_dirs");
        if (notNull(addDirs)) {
            if (!addDirs.isArray()) {
                throw new BadRequestException("add_dirs must be an array of strings");
            }
            for (ONode dir : addDirs.getArray()) {
                String val = dir.getString();
                if (val == null || val.trim().isEmpty()) {
                    throw new BadRequestException("add_dirs entries must be non-empty strings");
                }
                req.argv.add("--add-dir=" + val);
            }
        }
    }

    private void appendToolList(List<String> argv, String flagPrefix, ONode node) throws BadRequestException {
        if (isNull(node)) {
            return;
        }
        if (!node.isArray()) {
            throw new BadRequestException(flagPrefix.substring(2) + " must be an array of strings");
        }
        List<String> entries = new ArrayList<>();
        for (ONode e : node.getArray()) {
            String val = e.getString();
            if (val == null || val.trim().isEmpty()) {
                throw new BadRequestException(flagPrefix.substring(2) + " entries must be non-empty strings");
            }
            entries.add(val.trim());
        }
        if (!entries.isEmpty()) {
            argv.add(flagPrefix + String.join(",", entries));
        }
    }

    /**
     * workspace 白名单：只能取服务端已注册工作区（含默认 launch），拒绝任意路径。
     * 未指定时用默认工作区。
     */
    private void applyWorkspace(NormalizedRequest req, ONode workspace) throws WorkspaceNotFoundException {
        List<WorkspaceMeta> known = listKnownWorkspaces();

        if (isNull(workspace)) {
            WorkspaceMeta meta = known.get(0); // 首项即默认工作区
            req.workspacePath = meta.getPath();
            req.workspaceName = meta.getName();
            return;
        }

        String nameOrId = workspace.getString();
        if (nameOrId == null || nameOrId.trim().isEmpty()) {
            WorkspaceMeta meta = known.get(0);
            req.workspacePath = meta.getPath();
            req.workspaceName = meta.getName();
            return;
        }
        nameOrId = nameOrId.trim();

        // 依次按 id / name / path 在已注册工作区中匹配
        for (WorkspaceMeta meta : known) {
            if (nameOrId.equals(meta.getId()) || nameOrId.equals(meta.getName())
                    || nameOrId.equals(meta.getPath())) {
                req.workspacePath = meta.getPath();
                req.workspaceName = meta.getName();
                return;
            }
        }
        throw new WorkspaceNotFoundException(
                "Workspace not found: " + nameOrId + ". Use /web/workspace/list to see registered workspaces.");
    }

    /**
     * 会话锁标识：session_id > resume > continue（与 PrintMode.resolveSession 同序）
     */
    private String resolveLockSessionId(NormalizedRequest req) {
        for (String flag : req.argv) {
            if (flag.startsWith("--session-id=")) {
                return flag.substring("--session-id=".length());
            }
            if (flag.startsWith("--resume=")) {
                return flag.substring("--resume=".length());
            }
        }
        if (req.argv.contains("--continue")) {
            return "cli"; // PrintMode.resolveSession: continue → "cli"
        }
        return null; // 无会话维度，不锁（服务端随机 print-xxxx）
    }

    private static boolean isNull(ONode node) {
        return node == null || node.isNull();
    }

    private static boolean notNull(ONode node) {
        return node != null && !node.isNull();
    }
}
