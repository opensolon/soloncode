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
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.talents.mount.SkillDir;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.command.builtin.*;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

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
 *   <li><b>Git 集成</b>：仓库状态检测、初始化、Diff 查看、文件内容获取、提交（委派给 {@link GitService}）</li>
 *   <li><b>文件浏览</b>：工作区目录结构浏览、文件搜索、文件内容读取（委派给 {@link FileService}）</li>
 * </ul>
 *
 * <h3>架构位置</h3>
 * <p>位于 {@code portal.web} 层，是 Solon MVC 的 Controller。
 * 向上对接浏览器前端，向下通过 {@link WebGate}（WebSocket 推送通道）和
 * {@link HarnessEngine}（AI Agent 引擎）完成实际业务处理。</p>
 *
 * @author oisin 2026-3-13
 * @author noear 2026-4-18
 * @see WebGate    WebSocket 推送网关
 * @see GitService  Git 业务逻辑服务
 * @see FileService 文件浏览业务逻辑服务
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

    /** Git 业务逻辑服务，封装工作区 Git 操作 */
    private final GitService gitService;

    /** 文件业务逻辑服务，封装工作区文件浏览、搜索、读取操作 */
    private final FileService fileService;

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
        this.gitService = new GitService(engine.getWorkspace(), engine);
        this.fileService = new FileService(engine.getWorkspace());

        // 注入 Web 端 Loop 任务执行器：同步等待本轮 AI 响应结束，捕获文本结果用于 goal 检测。
        if (loopScheduler != null) {
            // 会话繁忙守卫：session 正在执行任务时，loop 定时触发跳过本次执行
            loopScheduler.addBusyChecker(sessionId -> {
                if (sessionId == null || !sessionId.startsWith("web-")) {
                    return false;
                }
                return webGate.isSessionBusy(sessionId);
            });

            loopScheduler.addTaskExecutor((sessionId, prompt, agentName) -> {
                if (sessionId.startsWith("web-") == false) {
                    return null;
                }

                // 如果指定了 agentName，将 prompt 拼接为 @agentName prompt 格式
                // WebGate.onChatInput 内部通过 input.startsWith("@") 识别并路由到对应 agent
                String effectiveInput = prompt;
                if (agentName != null && !agentName.isEmpty()) {
                    effectiveInput = "@" + agentName + " " + prompt;
                }

                // Loop 任务可能长时间执行（数小时），使用 Loop 专用无限等待版本
                return webGate.safeChatInputAndCaptureLoop(sessionId, effectiveInput, "Loop");
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
        data.put("workspace", engine.getWorkspace());
        data.put("workname", getLastSegment(engine.getWorkspace()));
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
        Path sessionsPath = Paths.get(engine.getWorkspace(), engine.getHarnessSessions()).toAbsolutePath().normalize();
        File sessionsDir = sessionsPath.toFile();
        List<Map> data = new ArrayList<>();

        if (sessionsDir.exists() && sessionsDir.isDirectory()) {
            File[] dirs = sessionsDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("web-"));
            if (dirs != null) {
                Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());

                for (File dir : dirs) {
                    String sid = dir.getName();
                    // 优先使用自定义标签
                    String label = null;
                    File labelFile = new File(dir, "label.txt");
                    if (labelFile.exists()) {
                        try (BufferedReader lblReader = new BufferedReader(
                                new InputStreamReader(new FileInputStream(labelFile), "UTF-8"))) {
                            label = lblReader.readLine();
                        } catch (Exception ignored) {
                        }
                    }

                    if (Assert.isEmpty(label)) {
                        File msgFile = new File(dir, sid + ".messages.ndjson");
                        if (!msgFile.exists()) continue;

                        label = extractFirstUserMessage(msgFile);
                    }

                    if (Assert.isEmpty(label)) {
                        continue;
                    }

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sessionId", sid);
                    item.put("label", label.length() > 30 ? label.substring(0, 30) + "..." : label);
                    item.put("time", dir.lastModified());
                    data.add(item);

                    //恢复定时任务
                    loopScheduler.restore(sid);
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

        Path sessionPath = Paths.get(engine.getWorkspace(), engine.getHarnessSessions(), sessionId).toAbsolutePath().normalize();
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
     * @param label     新的会话标签文本
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

        Path sessionPath = Paths.get(engine.getWorkspace(), engine.getHarnessSessions(), sessionId).toAbsolutePath().normalize();
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

        for (ChatConfig config : engine.getModels()) {
            if (config.isEnabled()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("model", config.getModel());
                item.put("name", config.getNameOrModel());
                item.put("description", config.getDescriptionOrModel());
                item.put("contextLength", config.getContextLength());
                list.add(item);
            }
        }
        list.sort((a, b) -> {
            String nameA = (String) a.getOrDefault("name", "");
            String nameB = (String) b.getOrDefault("name", "");
            return nameA.compareToIgnoreCase(nameB);
        });

        data.put("list", list);

        if (Assert.isNotEmpty(list)) {
            if (Assert.isNotEmpty(sessionId)) {
                AgentSession session = engine.getSession(sessionId);
                String selected = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);

                if (selected != null) {
                    selected = engine.getModelOrDef(selected).getNameOrModel();
                } else {
                    selected = engine.getModelOrDef(null).getNameOrModel();
                }

                data.put("selected", selected);
            } else {
                data.put("selected", engine.getModelOrDef(null).getNameOrModel());
            }
        } else {
            data.put("selected", "");
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

        session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, modelName);

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
        Path sessionsPath = Paths.get(engine.getWorkspace(), engine.getHarnessSessions(), sessionId).toAbsolutePath().normalize();
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
            Path sessionsPath = Paths.get(engine.getWorkspace(), engine.getHarnessSessions(), sessionId).toAbsolutePath().normalize();
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
        for (SkillDir skill : engine.getSkills()) {
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
            item.put("mountAlias", skill.getMountAlias());
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



    // ==================== Git 集成（委派给 GitService） ====================

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
        return gitService.status();
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
        return gitService.init(initialCommit);
    }

    @Get
    @Mapping("/web/chat/git/diff")
    public Result<Map> gitDiff(@Param(value = "path", required = false) String path) throws Exception {
        return gitService.diff(path);
    }

    @Post
    @Mapping("/web/chat/git/stage")
    public Result<Map> gitStage(@Body String body) throws Exception {
        String path = parseJsonPath(body);
        return gitService.stage(path);
    }

    @Post
    @Mapping("/web/chat/git/unstage")
    public Result<Map> gitUnstage(@Body String body) throws Exception {
        String path = parseJsonPath(body);
        return gitService.unstage(path);
    }

    @Get
    @Mapping("/web/chat/git/file-content")
    public Result<Map> gitFileContent(@Param("path") String path,
                                      @Param(value = "ref", required = false) String ref) throws Exception {
        return gitService.fileContent(path, ref);
    }

    @Post
    @Mapping("/web/chat/git/commit")
    public Result<Map> gitCommit(@Body String body) throws Exception {
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
            }
        }
        return gitService.commit(message, files);
    }

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

        return gitService.summary(sessionId, files);
    }

    /**
     * 从 JSON 请求体中解析 path 字段。
     *
     * @param body JSON 字符串，如 { "path": "src/App.java" }
     * @return path 值，解析失败返回 null
     */
    private String parseJsonPath(String body) {
        if (body != null && !body.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(body);
                if (json != null && json.isObject()) {
                    ONode pathNode = json.get("path");
                    if (pathNode != null && pathNode.isString()) {
                        return pathNode.getString();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }


    // ==================== 循环任务管理 ====================

    /**
     * 获取当前会话的循环任务列表（含已停用的）。
     */
    @Get
    @Mapping("/web/chat/loop/list")
    public Result<List<Map>> loopList(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }

        List<LoopTask> tasks = loopScheduler.listAll(sessionId);
        List<Map> data = new ArrayList<>();
        for (LoopTask t : tasks) {
            Map<String, Object> item = buildTaskMap(t);
            data.add(item);
        }
        return Result.succeed(data);
    }

    /**
     * 构建任务 Map（通用方法，供 list/get 复用）
     */
    private Map<String, Object> buildTaskMap(LoopTask t) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", t.getId());
        item.put("type", t.getType().name());  // 任务类型（LOOP / GOAL）
        item.put("prompt", t.getPrompt());
        item.put("intervalMinutes", t.getIntervalMinutes());
        if (t.getCron() != null) item.put("cron", t.getCron());
        item.put("enabled", t.isEnabled());
        item.put("cancelled", t.isCancelled());
        item.put("running", t.isRunning());
        item.put("currentIteration", t.getCurrentIteration());
        if (t.getLastResult() != null) item.put("lastResult", t.getLastResult());
        if (t.getLastExecutedAt() != null) item.put("lastExecutedAt", t.getLastExecutedAt().toString());


        item.put("maxIterations", t.getMaxIterations());
        item.put("runNow", t.isRunNow());

        // ★ P1: 预算字段
        if (t.getMaxTokens() != null) item.put("maxTokens", t.getMaxTokens());
        if (t.getMaxDurationMs() != null) item.put("maxDurationMs", t.getMaxDurationMs());

        // ★ P0: Goal 状态信息
        if (t.isGoalMode()) {
            GoalState gs = t.getGoalState();
            Map<String, Object> goalMap = new LinkedHashMap<>();
            goalMap.put("condition", gs.getCondition());
            goalMap.put("status", gs.getStatus().name());
            goalMap.put("iteration", t.getCurrentIteration());
            goalMap.put("consumedTokens", gs.getConsumedTokens());
            goalMap.put("maxTokens", gs.getMaxTokens());
            if (gs.getStartEpochMs() > 0) {
                goalMap.put("startedAt", Instant.ofEpochMilli(gs.getStartEpochMs()).toString());
            }

            item.put("goal", goalMap);
        }

        return item;
    }

    /**
     * 获取单个循环任务详情（用于编辑回填）。
     */
    @Get
    @Mapping("/web/chat/loop/get")
    public Result<Map> loopGet(@Param("sessionId") String sessionId,
                               @Param("taskId") String taskId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.trim().isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        List<LoopTask> tasks = loopScheduler.listAll(sessionId);
        for (LoopTask t : tasks) {
            if (t.getId().equals(taskId)) {
                return Result.succeed(buildTaskMap(t));
            }
        }
        return Result.failure(404, "Task not found");
    }

    /**
     * 新增循环任务。
     */
    @Post
    @Mapping("/web/chat/loop/add")
    public Result loopAdd(@Param("sessionId") String sessionId,
                          @Param("prompt") String prompt,
                          @Param(value = "intervalMinutes", required = false) Integer intervalMinutes,
                          @Param(value = "cron", required = false) String cron,
                          @Param(value = "type", required = false) String type,
                           @Param(value = "maxIterations", required = false) Integer maxIterations,
                          @Param(value = "runNow", required = false) Boolean runNow,
                          @Param(value = "maxTokens", required = false) Long maxTokens,
                          @Param(value = "maxDurationMs", required = false) Long maxDurationMs) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            return Result.failure(400, "prompt is required");
        }

        String workspace = engine.getWorkspace();
        String harnessSessions = engine.getHarnessSessions();

        // 确定任务类型
        LoopTask.TaskType taskType = (type != null && "GOAL".equalsIgnoreCase(type))
                ? LoopTask.TaskType.GOAL
                : LoopTask.TaskType.HEARTBEAT;

        // 初始化状态目录
        int interval = intervalMinutes != null ? intervalMinutes : 5;
        LoopTask task = new LoopTask(
                prompt, interval, cron,
                taskType,
                maxIterations,
                runNow != null && runNow
        );
        // ★ P1: 预算字段
        if (maxTokens != null) task.setMaxTokens(maxTokens);
        if (maxDurationMs != null) task.setMaxDurationMs(maxDurationMs);

        try {
            LoopStateManager.init(workspace, task.getId(), prompt);
            loopScheduler.schedule(sessionId, task);
        } catch (IllegalStateException e) {
            return Result.failure(400, e.getMessage());
        }

        return Result.succeed(task.getId());
    }

    /**
     * 更新循环任务定义。
     */
    @Post
    @Mapping("/web/chat/loop/update")
    public Result loopUpdate(@Param("sessionId") String sessionId,
                             @Param("taskId") String taskId,
                             @Param(value = "prompt", required = false) String prompt,
                             @Param(value = "intervalMinutes", required = false) Integer intervalMinutes,
                             @Param(value = "cron", required = false) String cron,
                             @Param(value = "type", required = false) String type,
                             @Param(value = "channelNotify", required = false) String channelNotify,
                             @Param(value = "maxIterations", required = false) Integer maxIterations,
                             @Param(value = "runNow", required = false) Boolean runNow,
                             @Param(value = "maxTokens", required = false) Long maxTokens,
                             @Param(value = "maxDurationMs", required = false) Long maxDurationMs) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        String workspace = engine.getWorkspace();
        String harnessSessions = engine.getHarnessSessions();

        LoopTask existing = loopScheduler.getTaskById(sessionId, taskId);
        if (existing == null) {
            return Result.failure(404, "Task not found");
        }

        // 基于现有任务构建新任务（保留 id、createdAt、expireAt 等）
        int interval = intervalMinutes != null ? intervalMinutes : existing.getIntervalMinutes();
        String effectiveCron = cron != null ? cron : existing.getCron();
        String effectivePrompt = (prompt != null && !prompt.trim().isEmpty()) ? prompt.trim() : existing.getPrompt();
        LoopTask.TaskType newType = (type != null) ? LoopTask.TaskType.valueOf(type.toUpperCase()) : null;

        LoopTask newTask = existing.copyWithUpdate(
                effectivePrompt, interval, effectiveCron,
                newType,
                maxIterations != null ? maxIterations : existing.getMaxIterations(),
                runNow != null ? runNow : existing.isRunNow(),
                maxTokens,
                maxDurationMs
        );

        // 保留 enabled
        newTask.setEnabled(existing.isEnabled());

        loopScheduler.update(sessionId, taskId, newTask);
        return Result.succeed();
    }

    // ==================== Goal 管理端点 (P0) ====================

    /**
     * 暂停 goal 调度
     */
    @Post
    @Mapping("/web/chat/loop/goal-pause")
    public Result loopGoalPause(@Param("sessionId") String sessionId,
                                @Param("taskId") String taskId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        GoalState gs = task.getGoalState();
        if (gs.getStatus() != GoalState.Status.PURSUING) {
            return Result.failure(400, "Goal cannot be paused in state: " + gs.getStatus());
        }

        loopScheduler.pauseGoal(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 恢复 goal 调度
     */
    @Post
    @Mapping("/web/chat/loop/goal-resume")
    public Result loopGoalResume(@Param("sessionId") String sessionId,
                                 @Param("taskId") String taskId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        GoalState gs = task.getGoalState();
        if (gs.getStatus() != GoalState.Status.PAUSED) {
            return Result.failure(400, "Goal cannot be resumed in state: " + gs.getStatus()
                    + " (only PAUSED or BLOCKED can be resumed)");
        }

        loopScheduler.resumeGoal(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 清除 goal（任务保留，仅清除 goal 标记）
     */
    @Post
    @Mapping("/web/chat/loop/goal-clear")
    public Result loopGoalClear(@Param("sessionId") String sessionId,
                                @Param("taskId") String taskId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        loopScheduler.clearGoal(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 获取 goal 详细状态（含完整评估历史）
     */
    @Post
    @Mapping("/web/chat/loop/goal-status")
    public Result<Map> loopGoalStatus(@Param("sessionId") String sessionId,
                                      @Param("taskId") String taskId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        GoalState gs = task.getGoalState();
        Map<String, Object> goalMap = new LinkedHashMap<>();
        goalMap.put("condition", gs.getCondition());
        goalMap.put("status", gs.getStatus().name());
        goalMap.put("iteration", task.getCurrentIteration());
        goalMap.put("consumedTokens", gs.getConsumedTokens());
        goalMap.put("maxTokens", gs.getMaxTokens());

        if (gs.getStartEpochMs() > 0) {
            goalMap.put("startedAt", Instant.ofEpochMilli(gs.getStartEpochMs()).toString());
        }

        return Result.succeed(goalMap);
    }

    /**
     * 删除循环任务。
     */
    @Post
    @Mapping("/web/chat/loop/remove")
    public Result loopRemove(@Param("sessionId") String sessionId, @Param("taskId") String taskId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(400, "the task does not exist.");
        }

        loopScheduler.remove(sessionId, task);
        return Result.succeed();
    }

    /**
     * 启用/停用循环任务（toggle）。
     */
    @Post
    @Mapping("/web/chat/loop/toggle")
    public Result loopToggle(@Param("sessionId") String sessionId, @Param("taskId") String taskId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        loopScheduler.toggle(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 手动触发一次循环任务执行。
     */
    @Post
    @Mapping("/web/chat/loop/trigger")
    public Result loopTrigger(@Param("sessionId") String sessionId, @Param("taskId") String taskId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        loopScheduler.trigger(sessionId, taskId);
        return Result.succeed();
    }



    // ==================== 工具方法 ====================

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
     * 获取指定会话的 TODO 列表。
     * <p>从会话对应的 TODO.md 文件中解析 checkbox 任务项，返回结构化的任务列表及统计信息。</p>
     *
     * @param sessionId 会话 ID
     * @return 包含 exists、raw、items、stats 的结果对象
     */
    @Get
    @Mapping("/web/chat/todos")
    public Result<Map> todos(@Param("sessionId") String sessionId) {
        if (sessionId == null || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure(400, "Invalid sessionId");
        }

        Path todoPath = engine.getTodoTalent().getTodoPath(engine.getWorkspace(), sessionId);

        Map<String, Object> data = new LinkedHashMap<>();

        if (!Files.exists(todoPath)) {
            data.put("exists", false);
            data.put("items", new ArrayList<>());
            Map<String, Integer> stats = new LinkedHashMap<>();
            stats.put("total", 0);
            stats.put("pending", 0);
            stats.put("inProgress", 0);
            stats.put("done", 0);
            data.put("stats", stats);
            return Result.succeed(data);
        }

        try {
            String raw = new String(Files.readAllBytes(todoPath), "UTF-8");
            data.put("exists", true);
            data.put("raw", raw);

            List<Map> items = new ArrayList<>();
            String currentGroup = "";
            int total = 0, pending = 0, inProgress = 0, done = 0;

            String[] lines = raw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];

                // 匹配 ## 标题行作为 group
                if (line.matches("^\\s*##\\s+.+$")) {
                    currentGroup = line.replaceFirst("^\\s*##\\s+", "").trim();
                    continue;
                }

                // 匹配 checkbox 行: - [ ] / - [/] / - [x] / - [X]
                if (line.matches("^\\s*-\\s*\\[( |x|X|/)\\]\\s+.+$")) {
                    total++;

                    char statusChar = line.replaceAll("^\\s*-\\s*\\[([ xX/])]\\s+.+$", "$1").charAt(0);
                    String status;
                    if (statusChar == ' ') {
                        status = "pending";
                        pending++;
                    } else if (statusChar == '/') {
                        status = "in_progress";
                        inProgress++;
                    } else {
                        status = "done";
                        done++;
                    }

                    String text = line.replaceFirst("^\\s*-\\s*\\[[ xX/]]\\s+", "").trim();

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("line", i + 1);
                    item.put("status", status);
                    item.put("text", text);
                    item.put("raw", line.trim());
                    item.put("group", currentGroup);
                    items.add(item);
                }
            }

            data.put("items", items);

            Map<String, Integer> stats = new LinkedHashMap<>();
            stats.put("total", total);
            stats.put("pending", pending);
            stats.put("inProgress", inProgress);
            stats.put("done", done);
            data.put("stats", stats);

            return Result.succeed(data);
        } catch (Exception e) {
            LOG.error("Failed to read TODO for session {}: {}", sessionId, e.getMessage());
            return Result.failure(500, e.getMessage());
        }
    }

    // ==================== 文件浏览（委派给 FileService） ====================

    /**
     * 工作区文件树浏览接口。
     *
     * @see FileService#tree(String, Integer)
     */
    @Get
    @Mapping("/web/chat/filer/tree")
    public Result<List<Map>> fileTree(@Param(value = "path", required = false) String path,
                                      @Param(value = "depth", required = false) Integer depth) throws Exception {
        return fileService.tree(path, depth);
    }

    /**
     * 工作区文件搜索接口。
     *
     * @see FileService#search(String)
     */
    @Get
    @Mapping("/web/chat/filer/search")
    public Result<List<Map>> fileSearch(@Param("keyword") String keyword) throws Exception {
        return fileService.search(keyword);
    }

    /**
     * 读取工作区文件内容接口。
     *
     * @see FileService#read(String)
     */
    @Get
    @Mapping("/web/chat/filer/read")
    public Result<Map> fileRead(@Param("path") String path) throws Exception {
        return fileService.read(path);
    }
}