/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.portal.web;

import org.noear.snack4.ONode;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessFlags;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.skills.cli.SkillDir;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Web 门户控制器 —— SolonCode Web UI 的核心 HTTP 入口。
 *
 * <p>职责：接收前端浏览器的 HTTP 请求，将聊天输入、会话管理、Git 操作、文件树浏览等
 * 业务委派给 {@link WebGate}（WebSocket 推送）和 {@link HarnessEngine}（AI 引擎）处理。</p>
 *
 * <h3>主要功能分组</h3>
 * <ul>
 *   <li><b>页面入口与元信息</b>：首页重定向、应用标题/版本/工作区路径查询</li>
 *   <li><b>聊天会话管理</b>：会话列表加载、删除、重命名、消息历史、回退、中断</li>
 *   <li><b>模型管理</b>：可用模型列表查询、当前会话模型切换</li>
 *   <li><b>聊天输入</b>：接收用户消息与附件，路由到 WebGate 进行 AI 处理</li>
 *   <li><b>Git 集成</b>：仓库状态检测、初始化、Diff 查看、文件内容获取、提交</li>
 *   <li><b>文件树浏览</b>：工作区目录结构的层级化浏览</li>
 * </ul>
 *
 * <h3>架构位置</h3>
 * <p>位于 {@code portal.web} 层，是 Solon MVC 的 Controller。
 * 向上对接浏览器前端，向下通过 {@link WebGate}（WebSocket 推送通道）和
 * {@link HarnessEngine}（AI Agent 引擎）完成实际业务处理。</p>
 *
 * @author oisin 2026-3-13
 * @author noear 2026-4-18
 * @see WebGate WebSocket 推送网关
 * @see HarnessEngine AI Agent 执行引擎
 */
public class WebController {
    /** 日志记录器 */
    private static final Logger LOG = LoggerFactory.getLogger(WebController.class);

    /** AI Agent 执行引擎，提供会话管理、模型配置、命令注册等核心能力 */
    private final HarnessEngine engine;

    /** WebSocket 推送网关，负责将 AI 处理结果实时推送到前端浏览器 */
    private final WebGate webGate;

    /** 循环调度器，用于恢复和管理 Web 端的定时/循环 AI 任务 */
    private final LoopScheduler loopScheduler;

    /**
     * 文件树浏览时排除的目录名称集合。
     * <p>包含各类构建产物、IDE 配置、版本控制等无需展示的目录，
     * 如 .git、.idea、node_modules、target、__pycache__ 等。</p>
     */
    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".idea", ".soloncode", "node_modules", "target", "__pycache__", ".gradle", ".mvn", "build"
    ));

    /**
     * 构造函数：初始化核心依赖并注册 Web 端 Loop 任务执行器。
     *
     * @param engine        AI Agent 执行引擎
     * @param webGate       WebSocket 推送网关
     * @param loopScheduler 循环任务调度器，可为 null（无循环任务场景）
     */
    public WebController(HarnessEngine engine, WebGate webGate, LoopScheduler loopScheduler) {
        this.engine = engine;
        this.webGate = webGate;
        this.loopScheduler = loopScheduler;

        // 注入 Web 端 Loop 任务执行器：异步执行 AI 任务，通过 WebGate WebSocket 推送到前端
        if (loopScheduler != null) {
            loopScheduler.addTaskExecutor((sessionId, prompt) -> {
                if (sessionId.startsWith("web-") == false) {
                    return;
                }

                webGate.safeChatInput(sessionId, prompt, "Loop");
            });
        }
    }

    /**
     * 首页入口：将根路径请求转发到静态页面 web.html。
     *
     * @param ctx Solon 请求上下文
     * @throws Throwable 转发异常
     */
    @Get
    @Mapping("/")
    public void index(Context ctx) throws Throwable {
        ctx.forward("/web.html");
    }

    /**
     * 页面元信息接口：供前端启动时一次性获取应用标题、版本号、工作区路径等基础信息。
     *
     * @return 包含 appTitle、appVersion、workspace、workname 的结果对象
     * @throws Exception 读取配置异常
     */
    @Get
    @Mapping("/web/chat/meta")
    public Result<Map> meta() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appTitle", Solon.cfg().appTitle());
        data.put("appVersion", AgentFlags.getVersion());
        data.put("workspace", engine.getProps().getWorkspace());
        data.put("workname", getLastSegment(engine.getProps().getWorkspace()));
        return Result.succeed(data);
    }

    /**
     * 从文件路径中提取最后一段（即文件名或目录名）。
     *
     * @param pathStr 完整文件路径字符串
     * @return 路径最后一段，若路径为空则返回空字符串
     */
    private static String getLastSegment(String pathStr) {
        Path path = Paths.get(pathStr);
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    // ==================== 会话管理 ====================

    /**
     * 加载 Web 端会话列表。
     * <p>扫描工作区 .soloncode/sessions 目录下以 "web-" 开头的会话文件夹，
     * 读取每个会话的标签（优先使用自定义标签，否则取首条用户消息），
     * 按最后修改时间倒序排列返回。同时恢复每个会话关联的循环任务。</p>
     *
     * @return 会话列表，每项包含 sessionId、label、time
     * @throws Exception 文件读取异常
     */
    @Get
    @Mapping("/web/chat/sessions")
    public Result<List<Map>> sessions() throws Exception {
        Path sessionsPath = Paths.get(engine.getProps().getWorkspace(), ".soloncode", "sessions").toAbsolutePath().normalize();
        File sessionsDir = sessionsPath.toFile();
        List<Map> data = new ArrayList<>();

        if (sessionsDir.exists() && sessionsDir.isDirectory()) {
            File[] dirs = sessionsDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("web-"));
            if (dirs != null) {
                Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());

                for (File dir : dirs) {
                    String sid = dir.getName();
                    File msgFile = new File(dir, sid + ".messages.ndjson");
                    if (!msgFile.exists()) continue;

                    // 优先使用自定义标签
                    String label = null;
                    File labelFile = new File(dir, "label.txt");
                    if (labelFile.exists()) {
                        try (BufferedReader lblReader = new BufferedReader(
                                new InputStreamReader(new FileInputStream(labelFile), "UTF-8"))) {
                            label = lblReader.readLine();
                        } catch (Exception ignored) {}
                    }
                    if (label == null || label.isEmpty()) {
                        label = extractFirstUserMessage(msgFile);
                    }
                    if (label == null || label.isEmpty()) continue;

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sessionId", sid);
                    item.put("label", label.length() > 30 ? label.substring(0, 30) + "..." : label);
                    item.put("time", dir.lastModified());
                    data.add(item);

                    //恢复定时任务
                    loopScheduler.restore(sid, engine.getProps().getWorkspace(), engine.getProps().getHarnessSessions());
                }
            }
        }

        return Result.succeed(data);
    }

    /**
     * 删除指定会话及其所有消息记录。
     * <p>执行路径安全检查后，递归删除会话目录下的所有文件。</p>
     *
     * @param sessionId 待删除的会话 ID（必须为 web- 前缀）
     * @return 操作结果
     * @throws Exception 文件删除异常
     */
    @Post
    @Mapping("/web/chat/sessions/delete")
    public Result deleteSession(@Param("sessionId") String sessionId) throws Exception {
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure();
        }

        Path sessionPath = Paths.get(engine.getProps().getWorkspace(), ".soloncode", "sessions", sessionId).toAbsolutePath().normalize();
        File sessionDir = sessionPath.toFile();

        if (sessionDir.exists() && sessionDir.isDirectory()) {
            deleteDirectory(sessionDir);
        }

        return Result.succeed();
    }

    /**
     * 重命名会话标签。
     * <p>在会话目录下写入 label.txt 文件保存自定义标签，标签最大长度 50 字符。</p>
     *
     * @param sessionId 待重命名的会话 ID
     * @param label      新的会话标签文本
     * @return 操作结果
     * @throws Exception 文件写入异常
     */
    @Post
    @Mapping("/web/chat/sessions/rename")
    public Result renameSession(@Param("sessionId") String sessionId, @Param("label") String label) throws Exception {
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure();
        }
        if (label == null || label.trim().isEmpty()) {
            return Result.failure(400, "Label is required");
        }
        // 限制标签长度
        if (label.length() > 50) {
            label = label.substring(0, 50);
        }

        Path sessionPath = Paths.get(engine.getProps().getWorkspace(), ".soloncode", "sessions", sessionId).toAbsolutePath().normalize();
        File labelFile = new File(sessionPath.toFile(), "label.txt");

        if (!sessionPath.toFile().exists() || !sessionPath.toFile().isDirectory()) {
            return Result.failure(404, "Session not found");
        }

        java.nio.file.Files.write(labelFile.toPath(), label.trim().getBytes("UTF-8"));

        return Result.succeed();
    }

    /**
     * 查询可用 AI 模型列表及当前选中模型。
     * <p>从引擎配置中获取所有可用模型，若指定了 sessionId 则返回该会话当前选中的模型，
     * 否则返回引擎默认主模型。</p>
     *
     * @param sessionId 可选的会话 ID，用于获取该会话当前选中的模型
     * @return 包含 list（模型列表）和 selected（当前选中模型名）的结果对象
     * @throws Exception 会话查询异常
     */
    @Get
    @Mapping("/web/chat/models")
    public Result<Map> models(@Param(value = "sessionId", required = false) String sessionId) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map> list = new ArrayList<>();

        for (ChatConfig config : engine.getProps().getModels()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("model", config.getModel());
            item.put("name", config.getNameOrModel());
            item.put("description", config.getDescriptionOrModel());
            item.put("provider", config.getProvider());
            item.put("apiUrl", config.getApiUrl());
            item.put("apiKey", config.getApiKey());
            list.add(item);
        }
        data.put("list", list);

        if (Assert.isNotEmpty(sessionId)) {
            AgentSession session = engine.getSession(sessionId);
            String selected = session.getContext().getOrDefault(HarnessFlags.VAR_MODEL_SELECTED,
                    engine.getMainModel().getNameOrModel());

            data.put("selected", selected);
        } else {
            data.put("selected", engine.getMainModel().getNameOrModel());
        }

        return Result.succeed(data);
    }

    /**
     * 切换指定会话的 AI 模型。
     * <p>将目标模型名称写入会话上下文并更新快照，后续该会话的 AI 交互将使用新模型。</p>
     *
     * @param sessionId 会话 ID
     * @param modelName 目标模型名称
     * @return 操作结果
     * @throws Exception 会话操作异常
     */
    @Post
    @Mapping("/web/chat/models/select")
    public Result models_select(@Param("sessionId") String sessionId, @Param("modelName") String modelName) throws Exception {
        AgentSession session = engine.getSession(sessionId);

        session.getContext().put(HarnessFlags.VAR_MODEL_SELECTED, modelName);

        session.updateSnapshot();

        return Result.succeed();
    }

    /**
     * 获取指定会话的消息历史记录。
     * <p>从 ndjson 消息文件中逐行读取，解析每条消息的 role、content、createdAt 字段。</p>
     *
     * @param sessionId 会话 ID
     * @return 消息列表，每项包含 role、content、createdAt
     * @throws Exception 文件读取异常
     */
    @Get
    @Mapping("/web/chat/messages")
    public Result<List<Map>> messages(@Param("sessionId") String sessionId) throws Exception {
        List<Map> data = new ArrayList<>();
        Path sessionsPath = Paths.get(engine.getProps().getWorkspace(), ".soloncode", "sessions", sessionId).toAbsolutePath().normalize();
        File msgFile = new File(sessionsPath.toFile(), sessionId + ".messages.ndjson");

        if (msgFile.exists()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(msgFile), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    ONode node = ONode.ofJson(line);
                    String role = node.get("role").getString();
                    String content = node.get("content").getString();

                    if (role != null && content != null) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("role", role);
                        item.put("content", content);
                        item.put("createdAt", node.get("createdAt").getString());
                        data.add(item);
                    }
                }
            }
        }

        return Result.succeed(data);
    }

    /**
     * 中断指定会话的当前 AI 处理。
     * <p>执行 sessionId 安全校验后，委派给 WebGate 中断该会话正在进行的 AI 任务。</p>
     *
     * @param sessionId 待中断的会话 ID
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/interrupt")
    public Result interruptSession(@Param("sessionId") String sessionId) {
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure();
        }

        webGate.interruptSession(sessionId);

        return Result.succeed();
    }

    /**
     * 回退会话消息：删除指定会话最近 N 条消息记录。
     * <p>仅操作 ndjson 持久化文件（内存中的 AgentSession 会在重新生成时通过新的 prompt 重建上下文）。
     * 默认回退 2 条（即一对用户消息 + 助手回复）。</p>
     *
     * @param sessionId 会话 ID
     * @param count     回退条数，默认为 2
     * @return 操作结果
     * @throws Exception 文件读写异常
     */
    @Post
    @Mapping("/web/chat/rewind")
    public Result rewindSession(@Param("sessionId") String sessionId, @Param(value = "count", required = false) Integer count) throws Exception {
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure();
        }
        if (count == null || count <= 0) {
            count = 2; // 默认回退2条（用户+助手）
        }

        try {
            // 只操作 ndjson 文件（内存中的 AgentSession 在重新生成时会通过新的 prompt 重建上下文）
            Path sessionsPath = Paths.get(engine.getProps().getWorkspace(), ".soloncode", "sessions", sessionId).toAbsolutePath().normalize();
            File msgFile = new File(sessionsPath.toFile(), sessionId + ".messages.ndjson");
            if (msgFile.exists()) {
                // 读取现有消息
                java.util.List<String> lines = new ArrayList<>();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(msgFile), "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) lines.add(line);
                    }
                }
                // 移除最后 count 条
                int removeCount = Math.min(count, lines.size());
                for (int i = 0; i < removeCount; i++) {
                    lines.remove(lines.size() - 1);
                }
                // 重写文件
                StringBuilder sb = new StringBuilder();
                for (String l : lines) {
                    sb.append(l).append("\n");
                }
                java.nio.file.Files.write(msgFile.toPath(), sb.toString().getBytes("UTF-8"));
            }

            return Result.succeed();
        } catch (Exception e) {
            LOG.error("Rewind failed for session {}: {}", sessionId, e.getMessage());
            return Result.failure(500, e.getMessage());
        }
    }

    /**
     * 获取可用的命令和子代理列表。
     * <p>从引擎的命令注册表中获取所有非 CLI-Only 的命令，
     * 以及所有已注册的子代理（Agent），合并返回给前端用于命令补全和展示。</p>
     *
     * @return 命令/子代理列表，每项包含 name、description、type（command 或 subagent）
     */
    @Get
    @Mapping("/web/chat/hints")
    public Result<List<Map>> hints() {
        List<Map> data = new ArrayList<>();
        for (Command cmd : engine.getCommandRegistry().all()) {
            if (cmd.cliOnly()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", cmd.name());
            item.put("description", cmd.description());
            item.put("type", "command");
            data.add(item);
        }

        for (AgentDefinition definition : engine.getAgentManager().getAgents()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", definition.getName());
            item.put("description", definition.getDescription());
            item.put("type", "subagent");
            data.add(item);
        }

        Set<String> added = new HashSet<>();
        for (SkillDir skill : engine.getPoolManager().getSkillMap().values()) {
            if (added.contains(skill.getName())) {
                continue;
            } else {
                added.add(skill.getName());
            }

            String desc = skill.getDescription();
            if (desc != null) {
                // 取第一行，并限制最大长度
                int newlineIdx = desc.indexOf('\n');
                if (newlineIdx > 0) {
                    desc = desc.substring(0, newlineIdx);
                }
                if (desc.length() > 30) {
                    desc = desc.substring(0, 30) + "...";
                }
            }

            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", skill.getName());
            item.put("description", desc);
            item.put("type", "skill");
            data.add(item);
        }

        return Result.succeed(data);
    }

    /**
     * 聊天输入入口：解析请求参数后路由到 WebGate 处理。
     * <p>接收用户输入的文本消息、附件文件、模型选择和会话标识，
     * 经安全校验后委派给 {@link WebGate#onChatInput} 进行异步 AI 处理。
     * AI 处理结果通过 WebSocket 实时推送到前端，本接口仅返回简单成功响应。</p>
     *
     * @param ctx             Solon 请求上下文，用于读取请求头
     * @param input           用户输入的文本消息
     * @param attachments     上传的附件文件数组，可为 null
     * @param attachmentTypes 附件类型数组，与 attachments 一一对应
     * @param model           指定的 AI 模型名称，可为 null（使用默认模型）
     * @param sessionId       会话 ID，若为空则从请求头 X-Session-Id 获取
     * @return 操作结果（AI 结果通过 WebSocket 推送）
     */
    @Mapping("/web/chat/input")
    public Result chat_input(Context ctx, String input, UploadedFile[] attachments, String attachmentTypes[], String model, String sessionId) {
        try {
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = ctx.headerOrDefault("X-Session-Id", "web");
            }
            String sessionCwd = ctx.header("X-Session-Cwd");

            if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
                ctx.status(400);
                ctx.output("Invalid Session ID");
                return null;
            }

            if (Assert.isNotEmpty(sessionCwd)) {
                if (sessionCwd.contains("..")) {
                    ctx.status(400);
                    ctx.output("Invalid Session Cwd");
                    return null;
                }
            }

            String hitlAction = ctx.param("hitlAction");

            // 路由到 WebGate 处理（AI 结果通过 WebSocket 推送到前端）
            webGate.onChatInput(sessionId, sessionCwd, input, model, attachments, attachmentTypes, hitlAction);

            // 返回简单 JSON，前端通过 WebSocket 接收 AI 结果
            return Result.succeed();
        } catch (Throwable e) {
            LOG.error("[Web] chat_input error: {}", e.getMessage());
            return Result.failure(500, e.getMessage());
        }
    }



    // ==================== Git 集成 ====================

    /**
     * 进程执行结果封装，用于承载 Git 命令的标准输出、标准错误和退出码。
     */
    private static class ProcessResult {
        /** 进程退出码，0 表示成功 */
        int exitCode;
        /** 标准输出内容 */
        String stdout;
        /** 标准错误内容 */
        String stderr;
    }

    /**
     * 在指定工作目录下执行 Git 命令。
     * <p>禁用 Git 终端提示（GIT_TERMINAL_PROMPT=0），设置 10 秒超时保护，
     * 超时后强制终止进程并返回退出码 -1。</p>
     *
     * @param workDir 命令执行的工作目录
     * @param command 完整的命令及参数（如 "git", "status", "--porcelain=v1"）
     * @return 进程执行结果
     * @throws Exception 进程启动或流读取异常
     */
    private ProcessResult runGitCommand(File workDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.redirectErrorStream(false);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process proc = pb.start();
        String stdout = readGitStream(proc.getInputStream());
        String stderr = readGitStream(proc.getErrorStream());

        boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            ProcessResult result = new ProcessResult();
            result.exitCode = -1;
            result.stdout = "";
            result.stderr = "Command timed out after 10 seconds";
            return result;
        }

        ProcessResult result = new ProcessResult();
        result.exitCode = proc.exitValue();
        result.stdout = stdout;
        result.stderr = stderr;
        return result;
    }

    /**
     * 读取输入流的所有行并拼接为字符串。
     *
     * @param is 输入流
     * @return 拼接后的字符串内容
     * @throws Exception 流读取异常
     */
    private String readGitStream(java.io.InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Git 状态检测：返回 Git 可用性、仓库初始化状态、当前分支名及变更文件列表。
     * <p>依次执行以下检测：
     * <ol>
     *   <li>git --version 检测 Git 是否安装且可用</li>
     *   <li>git rev-parse 检测工作区是否已初始化为 Git 仓库</li>
     *   <li>git branch --show-current 获取当前分支名</li>
     *   <li>git status --porcelain=v1 解析变更文件（分为 changed、staged、untracked 三类）</li>
     * </ol></p>
     *
     * @return 包含 gitAvailable、initialized、branch、changed、staged、untracked 的结果对象
     * @throws Exception Git 命令执行异常
     */
    @Get
    @Mapping("/web/chat/git/status")
    public Result<Map> gitStatus() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        File workspaceDir = new File(engine.getProps().getWorkspace());

        // 1. 检测 git 是否可用
        try {
            ProcessResult checkGit = runGitCommand(workspaceDir, "git", "--version");
            if (checkGit.exitCode != 0) {
                data.put("gitAvailable", false);
                data.put("initialized", false);
                return Result.succeed(data);
            }
        } catch (Exception e) {
            data.put("gitAvailable", false);
            data.put("initialized", false);
            return Result.succeed(data);
        }
        data.put("gitAvailable", true);

        // 2. 检测是否是 git 仓库
        ProcessResult checkRepo = runGitCommand(workspaceDir, "git", "rev-parse", "--is-inside-work-tree");
        if (checkRepo.exitCode != 0) {
            data.put("initialized", false);
            data.put("workspacePath", workspaceDir.getAbsolutePath());
            return Result.succeed(data);
        }
        data.put("initialized", true);

        // 3. 获取分支名
        ProcessResult branchResult = runGitCommand(workspaceDir, "git", "branch", "--show-current");
        String branch = branchResult.stdout.trim();
        data.put("branch", branch.isEmpty() ? "master" : branch);

        // 4. 解析 git status --porcelain=v1
        ProcessResult statusResult = runGitCommand(workspaceDir, "git", "status", "--porcelain=v1");
        List<String> changed = new ArrayList<>();
        List<String> staged = new ArrayList<>();
        List<String> untracked = new ArrayList<>();

        for (String line : statusResult.stdout.split("\n")) {
            if (line.length() < 4) continue;
            String x = line.substring(0, 1);
            String y = line.substring(1, 2);
            String filePath = line.substring(3);

            // 规范化：去除尾部斜杠（git porcelain 对未跟踪目录可能输出 "?? dir/"）
            if (filePath.endsWith("/")) {
                filePath = filePath.substring(0, filePath.length() - 1);
            }

            if ("?".equals(x) && "?".equals(y)) {
                untracked.add(filePath);
            } else {
                if (!" ".equals(x) && "?".equals(x) == false) staged.add(filePath);
                if (!" ".equals(y) && "?".equals(y) == false) changed.add(filePath);
            }
        }
        data.put("changed", changed);
        data.put("staged", staged);
        data.put("untracked", untracked);

        return Result.succeed(data);
    }

    /**
     * 初始化 Git 仓库。
     * <p>在工作区执行 git init，自动生成 .gitignore 文件（仅当文件不存在时），
     * 可选执行初始提交（initialCommit=true 时）。</p>
     *
     * @param initialCommit 是否执行初始提交，默认为 false
     * @return 包含 initialized、branch 的结果对象
     * @throws Exception Git 命令执行异常
     */
    @Post
    @Mapping("/web/chat/git/init")
    public Result<Map> gitInit(@Param(value = "initialCommit", required = false) Boolean initialCommit) throws Exception {
        File workspaceDir = new File(engine.getProps().getWorkspace());

        // 安全校验：确认不是已有仓库
        ProcessResult check = runGitCommand(workspaceDir, "git", "rev-parse", "--is-inside-work-tree");
        if (check.exitCode == 0) {
            return Result.failure(400, "Already a git repository");
        }

        // 执行 git init
        ProcessResult initResult = runGitCommand(workspaceDir, "git", "init");
        if (initResult.exitCode != 0) {
            return Result.failure(500, "git init failed: " + initResult.stderr);
        }

        // 自动生成 .gitignore（仅当文件不存在时）
        File gitignore = new File(workspaceDir, ".gitignore");
        if (!gitignore.exists()) {
            String content = String.join("\n",
                    "# Auto-generated by SolonCode",
                    ".git/",
                    ".idea/",
                    ".soloncode/",
                    ".gradle/",
                    ".mvn/",
                    "node_modules/",
                    "target/",
                    "build/",
                    "__pycache__/",
                    "*.class",
                    "*.jar",
                    "*.log"
            );
            java.nio.file.Files.write(gitignore.toPath(), content.getBytes("UTF-8"));
        }

        // 可选：执行 initial commit
        if (Boolean.TRUE.equals(initialCommit)) {
            runGitCommand(workspaceDir, "git", "add", "-A");
            runGitCommand(workspaceDir, "git", "-c", "user.name=SolonCode",
                    "-c", "user.email=soloncode@noear.org",
                    "commit", "-m", "Initial commit");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("initialized", true);

        ProcessResult branchResult = runGitCommand(workspaceDir, "git", "branch", "--show-current");
        String branch = branchResult.stdout.trim();
        data.put("branch", branch.isEmpty() ? "master" : branch);

        return Result.succeed(data);
    }

    /**
     * 获取 Git Diff 内容。
     * <p>分别执行未暂存变更（git diff）和已暂存变更（git diff --cached）的 diff 查询，
     * 合并输出结果。单文件 diff 超过 2000 行时自动截断。同时返回 diff --stat 摘要信息。</p>
     *
     * @param path 可选的文件路径，用于查看指定文件的 diff；为空时查看全部变更
     * @return 包含 diff（完整差异内容）和 stat（变更统计摘要）的结果对象
     * @throws Exception Git 命令执行异常
     */
    @Get
    @Mapping("/web/chat/git/diff")
    public Result<Map> gitDiff(@Param(value = "path", required = false) String path) throws Exception {
        File workspaceDir = new File(engine.getProps().getWorkspace());

        // 安全校验：防止路径穿越
        if (path != null && (path.contains("..") || path.startsWith("/"))) {
            return Result.failure(400, "Invalid path");
        }

        boolean hasPath = path != null && !path.trim().isEmpty();

        // 未暂存的变更
        List<String> unstagedCmd = new ArrayList<>(Arrays.asList("git", "diff"));
        if (hasPath) { unstagedCmd.add("--"); unstagedCmd.add(path); }
        ProcessResult unstagedResult = runGitCommand(workspaceDir, unstagedCmd.toArray(new String[0]));

        // 已暂存的变更
        List<String> stagedCmd = new ArrayList<>(Arrays.asList("git", "diff", "--cached"));
        if (hasPath) { stagedCmd.add("--"); stagedCmd.add(path); }
        ProcessResult stagedResult = runGitCommand(workspaceDir, stagedCmd.toArray(new String[0]));

        // 合并 diff 输出
        String fullDiff = unstagedResult.stdout;
        if (!stagedResult.stdout.isEmpty()) {
            fullDiff += "\n" + stagedResult.stdout;
        }

        // 截断保护：单文件 diff 限制 2000 行
        if (hasPath) {
            String[] lines = fullDiff.split("\n");
            if (lines.length > 2000) {
                fullDiff = String.join("\n", Arrays.copyOf(lines, 2000))
                        + "\n\n... (差异过大，仅显示前 2000 行，请在终端查看完整 diff)";
            }
        }

        // stat 摘要
        List<String> statCmd = new ArrayList<>(Arrays.asList("git", "diff", "--stat"));
        if (hasPath) { statCmd.add("--"); statCmd.add(path); }
        ProcessResult statResult = runGitCommand(workspaceDir, statCmd.toArray(new String[0]));

        List<String> statCachedCmd = new ArrayList<>(Arrays.asList("git", "diff", "--cached", "--stat"));
        if (hasPath) { statCachedCmd.add("--"); statCachedCmd.add(path); }
        ProcessResult statCachedResult = runGitCommand(workspaceDir, statCachedCmd.toArray(new String[0]));

        String stat = statResult.stdout;
        if (!statCachedResult.stdout.isEmpty()) {
            stat += (stat.isEmpty() ? "" : "\n") + statCachedResult.stdout;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("diff", fullDiff);
        data.put("stat", stat);

        return Result.succeed(data);
    }

    /**
     * 将指定文件添加到 Git 暂存区（git add）。
     *
     * @param body JSON: { "path": "src/App.java" }
     * @return 包含 path 的结果对象
     * @throws Exception Git 命令执行异常
     */
    @Post
    @Mapping("/web/chat/git/stage")
    public Result<Map> gitStage(@Body String body) throws Exception {
        File workspaceDir = new File(engine.getProps().getWorkspace());
        ProcessResult check = runGitCommand(workspaceDir, "git", "rev-parse", "--is-inside-work-tree");
        if (check.exitCode != 0) {
            return Result.failure(400, "Not a git repository");
        }

        String path = null;
        if (body != null && !body.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(body);
                if (json != null && json.isObject()) {
                    ONode pathNode = json.get("path");
                    if (pathNode != null && pathNode.isString()) {
                        path = pathNode.getString();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (path == null || path.trim().isEmpty()) {
            return Result.failure(400, "Path is required");
        }
        if (path.contains("..") || path.startsWith("/")) {
            return Result.failure(400, "Invalid path");
        }

        ProcessResult addResult = runGitCommand(workspaceDir, "git", "add", "--", path);
        if (addResult.exitCode != 0) {
            return Result.failure(500, "git add failed: " + addResult.stderr);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        return Result.succeed(data);
    }

    /**
     * 将指定文件移出 Git 暂存区（git reset HEAD -- path）。
     *
     * @param body JSON: { "path": "src/App.java" }
     * @return 包含 path 的结果对象
     * @throws Exception Git 命令执行异常
     */
    @Post
    @Mapping("/web/chat/git/unstage")
    public Result<Map> gitUnstage(@Body String body) throws Exception {
        File workspaceDir = new File(engine.getProps().getWorkspace());
        ProcessResult check = runGitCommand(workspaceDir, "git", "rev-parse", "--is-inside-work-tree");
        if (check.exitCode != 0) {
            return Result.failure(400, "Not a git repository");
        }

        String path = null;
        if (body != null && !body.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(body);
                if (json != null && json.isObject()) {
                    ONode pathNode = json.get("path");
                    if (pathNode != null && pathNode.isString()) {
                        path = pathNode.getString();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (path == null || path.trim().isEmpty()) {
            return Result.failure(400, "Path is required");
        }
        if (path.contains("..") || path.startsWith("/")) {
            return Result.failure(400, "Invalid path");
        }

        ProcessResult resetResult = runGitCommand(workspaceDir, "git", "reset", "HEAD", "--", path);
        if (resetResult.exitCode != 0) {
            return Result.failure(500, "git reset failed: " + resetResult.stderr);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        return Result.succeed(data);
    }

    /**
     * 获取 Git 仓库中指定版本的文件内容。
     * <p>通过 git show ref:path 获取文件内容，默认 ref 为 HEAD。</p>
     *
     * @param path 文件路径（相对于仓库根目录）
     * @param ref  Git 引用（分支名、标签、提交哈希等），默认为 HEAD
     * @return 包含 content（文件内容文本）的结果对象
     * @throws Exception Git 命令执行异常
     */
    @Get
    @Mapping("/web/chat/git/file-content")
    public Result<Map> gitFileContent(@Param("path") String path,
                                      @Param(value = "ref", required = false) String ref) throws Exception {
        File workspaceDir = new File(engine.getProps().getWorkspace());

        if (path == null || path.contains("..") || path.startsWith("/")) {
            return Result.failure(400, "Invalid path");
        }
        if (ref == null || ref.isEmpty()) ref = "HEAD";

        ProcessResult result = runGitCommand(workspaceDir, "git", "show", ref + ":" + path);

        if (result.exitCode != 0) {
            return Result.failure(404, "File not found: " + result.stderr);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", result.stdout);
        return Result.succeed(data);
    }

    /**
     * Git 提交：支持精确文件列表或全量 add -A。
     * <p>请求体为 JSON 格式：{@code { "message": "提交信息", "files": ["a.java", "b.css"] }}。
     * 当 files 为空或缺失时退化为 git add -A（兼容旧调用方式）。
     * 提交时自动使用 SolonCode 作为提交者信息。</p>
     *
     * @param body JSON 格式的请求体，包含 message（提交信息）和 files（文件列表，可选）
     * @return 包含 stdout（Git 提交输出）的结果对象
     * @throws Exception Git 命令执行异常或 JSON 解析异常
     */
    @Post
    @Mapping("/web/chat/git/commit")
    public Result<Map> gitCommit(@Body String body) throws Exception {
        File workspaceDir = new File(engine.getProps().getWorkspace());

        // 安全校验：确认是 git 仓库
        ProcessResult check = runGitCommand(workspaceDir, "git", "rev-parse", "--is-inside-work-tree");
        if (check.exitCode != 0) {
            return Result.failure(400, "Not a git repository");
        }

        // 解析 JSON body
        String message = null;
        List<String> files = null;
        if (body != null && !body.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(body);
                if (json != null && json.isObject()) {
                    ONode msgNode = json.get("message");
                    if (msgNode != null && msgNode.isString()) {
                        message = msgNode.getString();
                    }
                    ONode filesNode = json.get("files");
                    if (filesNode != null && filesNode.isArray()) {
                        files = new ArrayList<>();
                        for (ONode f : filesNode.getArray()) {
                            files.add(f.getString());
                        }
                    }
                }
            } catch (Exception ignored) {
                // 非 JSON，忽略
            }
        }

        // 校验提交信息
        if (message == null || message.trim().isEmpty()) {
            return Result.failure(400, "Commit message is required");
        }

        // git add：有指定文件时精确暂存，否则 add -A
        ProcessResult addResult;
        if (files != null && !files.isEmpty()) {
            // 先清空暂存区，避免之前已暂存的非选中文件被一起提交
            runGitCommand(workspaceDir, "git", "reset", "HEAD", "--");

            List<String> addCmd = new ArrayList<>();
            addCmd.add("git");
            addCmd.add("add");
            addCmd.add("--");
            addCmd.addAll(files);
            addResult = runGitCommand(workspaceDir, addCmd.toArray(new String[0]));
        } else {
            addResult = runGitCommand(workspaceDir, "git", "add", "-A");
        }
        if (addResult.exitCode != 0) {
            return Result.failure(500, "git add failed: " + addResult.stderr);
        }

        // git commit
        ProcessResult commitResult = runGitCommand(workspaceDir, "git",
                "-c", "user.name=SolonCode",
                "-c", "user.email=soloncode@noear.org",
                "commit", "-m", message.trim());
        if (commitResult.exitCode != 0) {
            // 可能是 nothing to commit
            String err = commitResult.stderr.trim();
            if (err.isEmpty()) err = commitResult.stdout.trim();
            return Result.failure(500, "git commit failed: " + err);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stdout", commitResult.stdout.trim());
        return Result.succeed(data);
    }

    /**
     * Git 提交摘要生成：通过 AI 分析 diff 内容，生成简洁的提交信息。
     * <p>通过 sessionId 获取当前会话的 LLM 模型，收集指定文件的 diff，
     * 直接调用 ChatModel 进行纯文本生成（不走 ReAct Agent），HTTP 直接返回结果。</p>
     *
     * @param sessionId 当前会话 ID，用于获取用户选择的 LLM 模型
     * @param paths     需要分析的文件路径列表（JSON 数组格式）
     * @return 包含 summary（AI 生成的提交摘要）的结果对象
     */
    @Post
    @Mapping("/web/chat/git/summary")
    public Result<Map> gitSummary(@Param("sessionId") String sessionId,
                                  @Param("paths") String paths) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Result.failure(400, "sessionId is required");
        }
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }

        // 解析文件路径列表
        List<String> files = new ArrayList<>();
        if (paths != null && !paths.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(paths);
                if (json != null && json.isArray()) {
                    for (ONode f : json.getArray()) {
                        String p = f.getString();
                        if (p != null && !p.isEmpty()) {
                            files.add(p);
                        }
                    }
                }
            } catch (Exception e) {
                return Result.failure(400, "Invalid paths format, expected JSON array");
            }
        }
        if (files.isEmpty()) {
            return Result.failure(400, "paths is required");
        }

        try {
            // 通过 sessionId 获取会话，复用用户选择的 LLM 模型
            AgentSession session = engine.getSession(sessionId);
            String selectedModel = session.getContext().getOrDefault(
                    HarnessFlags.VAR_MODEL_SELECTED,
                    engine.getMainModel().getNameOrModel()
            );
            ChatModel chatModel = engine.getModelOrMain(selectedModel);

            // 收集 diff 内容（最多 15 个文件）
            File workspaceDir = new File(engine.getProps().getWorkspace());
            List<String> targetFiles = files.size() > 15 ? files.subList(0, 15) : files;
            StringBuilder combinedDiff = new StringBuilder();
            int MAX_DIFF_LINES = 500;
            int MAX_TOTAL_CHARS = 20000;

            for (String filePath : targetFiles) {
                if (filePath.contains("..") || filePath.startsWith("/")) continue;
                ProcessResult diffResult = runGitCommand(workspaceDir, "git", "diff", "--", filePath);
                ProcessResult cachedResult = runGitCommand(workspaceDir, "git", "diff", "--cached", "--", filePath);
                String diff = diffResult.stdout;
                if (!cachedResult.stdout.isEmpty()) {
                    diff += "\n" + cachedResult.stdout;
                }
                if (diff.trim().isEmpty()) {
                    diff = "(无差异内容)";
                }
                // 单文件截断 500 行
                String[] lines = diff.split("\n");
                if (lines.length > MAX_DIFF_LINES) {
                    diff = String.join("\n", Arrays.copyOf(lines, MAX_DIFF_LINES)) + "\n... (已截断)";
                }
                combinedDiff.append("\n\n=== ").append(filePath).append(" ===\n").append(diff);
            }

            // 总字符截断
            if (combinedDiff.length() > MAX_TOTAL_CHARS) {
                combinedDiff.setLength(MAX_TOTAL_CHARS);
                combinedDiff.append("\n\n... (总差异过大，已截断)");
            }

            String statInfo = targetFiles.size() + " 个文件" + (targetFiles.size() < files.size() ? "（仅前 15 个）" : "");

            // 构造系统提示词 + 用户消息，直接调 LLM（纯文本，不走 ReAct Agent）
            Prompt prompt = Prompt.of(
                    ChatMessage.ofSystem("你是一个专业的 Git 提交信息生成助手。"
                            + "你的任务是根据代码变更的 diff 内容，生成一条简洁、准确的提交摘要。\n"
                            + "要求：\n"
                            + "1. 使用中文\n"
                            + "2. 控制在 100 字以内\n"
                            + "3. 突出核心变更（新增、修改、删除、重构等）\n"
                            + "4. 使用祈使句风格（如'添加xxx功能'、'修复xxx问题'）\n"
                            + "5. 直接输出纯文本，不要使用 Markdown 格式、不要包含代码片段\n"
                            + "6. 如果变更涉及多个方面，按重要性排序，用分号分隔"),
                    ChatMessage.ofUser("请根据以下 Git diff 变更内容，生成提交信息摘要。\n\n"
                            + "变更统计：" + statInfo
                            + "\n\n--- Diff 内容 ---\n" + combinedDiff)
            );

            String summary = chatModel.prompt(prompt).call().getMessage().getContent();

            // 清理 Markdown 格式
            if (summary != null) {
                summary = summary.replace("**", "")
                        .replace("*", "")
                        .replaceAll("^#+\\s", "")
                        .replace("`", "")
                        .trim();
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("summary", summary != null ? summary : "");
            return Result.succeed(data);
        } catch (Exception e) {
            LOG.error("[Web] gitSummary error: {}", e.getMessage());
            return Result.failure(500, "生成摘要失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法与文件树浏览 ====================

    /**
     * 递归删除目录及其所有子文件和子目录。
     *
     * @param dir 待删除的目录
     */
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * 从 ndjson 消息文件中提取第一条用户（USER 角色）消息的内容。
     * <p>逐行读取消息文件，找到第一条 role 为 USER 的记录并返回其 content 字段。</p>
     *
     * @param msgFile ndjson 格式的消息文件
     * @return 第一条用户消息内容，若未找到则返回 null
     */
    private String extractFirstUserMessage(File msgFile) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(msgFile), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                ONode node = ONode.ofJson(line);
                String role = node.get("role").getString();
                if ("USER".equals(role)) {
                    return node.get("content").getString();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * 工作区文件树浏览接口。
     * <p>以工作区根目录为基准，按指定路径和深度返回目录结构。
     * 排除以点号开头的隐藏文件和 {@link #EXCLUDED_DIRS} 中的目录。</p>
     *
     * @param path  相对路径，基于工作区根目录；为空时从根目录开始
     * @param depth 展开深度，默认为 1（仅展开第一层）
     * @return 文件树列表，每项包含 name、path、type、expanded、children
     * @throws Exception 文件系统访问异常
     */
    @Get
    @Mapping("/web/chat/filer/tree")
    public Result<List<Map>> fileTree(@Param(value = "path", required = false) String path,
                                      @Param(value = "depth", required = false) Integer depth) throws Exception {
        if (depth == null || depth < 1) depth = 1;
        if (path == null) path = "";
        if (path.contains("..")) {
            return Result.failure(400, "Invalid path");
        }

        java.nio.file.Path workspace = java.nio.file.Paths.get(engine.getProps().getWorkspace()).toAbsolutePath().normalize();
        java.nio.file.Path target = workspace.resolve(path).toAbsolutePath().normalize();

        if (!target.startsWith(workspace)) {
            return Result.failure(403, "Access denied");
        }
        if (!target.toFile().exists() || !target.toFile().isDirectory()) {
            return Result.failure(404, "Directory not found");
        }

        List<Map> tree = buildTree(target, workspace, depth, 1);
        return Result.succeed(tree);
    }

    /**
     * 工作区文件搜索。
     * <p>递归扫描整个工作区，返回路径中包含关键词的文件列表。
     * 排除规则与文件树接口一致：隐藏文件和 EXCLUDED_DIRS 中的目录。</p>
     *
     * @param keyword 搜索关键词，匹配文件路径（大小写不敏感）
     * @return 匹配的文件列表，每项包含 name、path、type
     * @throws Exception 文件系统访问异常
     */
    @Get
    @Mapping("/web/chat/filer/search")
    public Result<List<Map>> fileSearch(@Param("keyword") String keyword) throws Exception {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Result.failure(400, "Keyword is required");
        }
        if (keyword.contains("..")) {
            return Result.failure(400, "Invalid keyword");
        }

        java.nio.file.Path workspace = java.nio.file.Paths.get(engine.getProps().getWorkspace()).toAbsolutePath().normalize();
        String kw = keyword.trim().toLowerCase();

        List<Map> results = new ArrayList<>();
        searchFiles(workspace.toFile(), workspace, kw, results, 0);

        if (results.size() > 200) {
            results = results.subList(0, 200);
        }

        return Result.succeed(results);
    }

    /**
     * 读取工作区文件内容。
     * <p>以工作区根目录为基准，读取指定路径的文件文本内容。
     * 支持安全路径校验和文件大小限制。</p>
     *
     * @param path 相对路径，基于工作区根目录
     * @return 文件信息，包含 content、path、name、size
     * @throws Exception 文件系统访问异常
     */
    @Get
    @Mapping("/web/chat/filer/read")
    public Result<Map> fileRead(@Param("path") String path) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            return Result.failure(400, "Path is required");
        }
        if (path.contains("..")) {
            return Result.failure(400, "Invalid path");
        }

        java.nio.file.Path workspace = java.nio.file.Paths.get(engine.getProps().getWorkspace()).toAbsolutePath().normalize();
        java.nio.file.Path target = workspace.resolve(path).toAbsolutePath().normalize();

        if (!target.startsWith(workspace)) {
            return Result.failure(403, "Access denied");
        }
        if (!target.toFile().exists() || target.toFile().isDirectory()) {
            return Result.failure(404, "File not found");
        }

        File file = target.toFile();
        // 限制文件大小：2MB
        if (file.length() > 2 * 1024 * 1024) {
            return Result.failure(413, "File too large (max 2MB)");
        }

        // 读取文件内容（尝试 UTF-8，失败回退系统默认编码）
        String content;
        try {
            content = new String(java.nio.file.Files.readAllBytes(target), "UTF-8");
        } catch (Exception e) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                content = sb.toString();
            } finally {
                if (reader != null) reader.close();
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content);
        data.put("path", path);
        data.put("name", file.getName());
        data.put("size", file.length());

        return Result.succeed(data);
    }

    /**
     * 递归构建文件树结构。
     * <p>对指定目录进行扫描，目录排在前面、文件排在后面，均按名称字典序排列。
     * 跳过以点号开头的隐藏文件和 {@link #EXCLUDED_DIRS} 中定义的目录。
     * 当达到最大深度时，目录节点不再展开（children 为 null）。</p>
     *
     * @param dir          当前扫描的目录路径
     * @param workspace    工作区根路径，用于计算相对路径
     * @param maxDepth     最大展开深度
     * @param currentDepth 当前递归深度
     * @return 当前层级的文件/目录信息列表
     */
    private List<Map> buildTree(java.nio.file.Path dir, java.nio.file.Path workspace, int maxDepth, int currentDepth) {
        File[] files = dir.toFile().listFiles();
        if (files == null) return Collections.emptyList();

        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        List<Map> result = new ArrayList<>();
        for (File f : files) {
            if (f.getName().startsWith(".") || EXCLUDED_DIRS.contains(f.getName())) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", f.getName());
            item.put("path", workspace.relativize(f.toPath().toAbsolutePath().normalize()).toString().replace('\\', '/'));
            item.put("type", f.isDirectory() ? "directory" : "file");

            if (f.isDirectory() && currentDepth < maxDepth) {
                item.put("expanded", true);
                item.put("children", buildTree(f.toPath(), workspace, maxDepth, currentDepth + 1));
            } else if (f.isDirectory()) {
                item.put("expanded", false);
                item.put("children", null);
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 递归搜索匹配关键词的文件。
     *
     * @param dir       当前扫描的目录
     * @param workspace 工作区根路径，用于计算相对路径
     * @param keyword   小写化后的搜索关键词
     * @param results   收集结果的列表
     * @param depth     当前递归深度，超过 20 层停止
     */
    private void searchFiles(File dir, java.nio.file.Path workspace, String keyword, List<Map> results, int depth) {
        if (depth > 20) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.getName().startsWith(".") || (f.isDirectory() && EXCLUDED_DIRS.contains(f.getName()))) continue;

            String relativePath = workspace.relativize(f.toPath().toAbsolutePath().normalize()).toString().replace('\\', '/');

            if (relativePath.toLowerCase().contains(keyword)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", f.getName());
                item.put("path", relativePath);
                item.put("type", f.isDirectory() ? "directory" : "file");
                results.add(item);
            }

            if (f.isDirectory()) {
                searchFiles(f, workspace, keyword, results, depth + 1);
            }
        }
    }
}