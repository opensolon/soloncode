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
package org.noear.solon.codecli.portal;

import org.noear.snack4.ONode;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessFlags;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandResult;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.command.WebCommandDispatcher;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.provider.ModelInfo;
import org.noear.solon.codecli.provider.ModelProvider;
import org.noear.solon.codecli.provider.ModelProviderFactory;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ModelAndView;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.web.sse.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Web Chat Controller
 * @author oisin 2026-3-13
 * @author noear 2026-4-18
 */
public class WebController {
    private static final Logger LOG = LoggerFactory.getLogger(WebController.class);

    private final HarnessEngine engine;
    private final WebStreamBuilder streamBuilder;
    private final ModelProviderFactory modelProviderFactory;
    private final LoopScheduler loopScheduler;
    private final WebSessionSink sessionSink;

    public WebController(HarnessEngine engine, LoopScheduler loopScheduler, ModelProviderFactory modelProviderFactory) {
        this.engine = engine;
        this.streamBuilder = new WebStreamBuilder(engine);
        this.modelProviderFactory = modelProviderFactory;
        this.loopScheduler = loopScheduler;
        this.sessionSink = new WebSessionSink();

        // 注入 Web 端 Loop 任务执行器：异步执行 AI 任务，流式推送到前端
        if (loopScheduler != null) {
            loopScheduler.setReactiveTaskExecutor((sessionId, prompt) -> {
                if (sessionId.startsWith("web-") == false) {
                    return null;
                }

                return executeLoopTaskAsync(sessionId, prompt);
            });
        }
    }

    /**
     * Web 端 Loop 任务异步执行：调用 AI，流式推送到前端，并收集结果摘要
     */
    private CompletableFuture<String> executeLoopTaskAsync(String sessionId, String prompt) {
        CompletableFuture<String> future = new CompletableFuture<>();

        try {
            AgentSession session = engine.getSession(sessionId);
            if(session.attrs().containsKey("disposable")){
                //说明当前还有任务在跑（则不执行 loop task）
                future.complete("ok");
                return future;
            }


            ChatModel chatModel = engine.getMainModel();

            StringBuilder resultBuilder = new StringBuilder();

            streamBuilder.buildStreamFlux(session, chatModel, null, Prompt.of(prompt))
                    .filter(line -> !"[DONE]".equals(line))
                    .doOnNext(line -> {
                        // 1) 广播到前端 SSE
                        sessionSink.emit(sessionId, line);
                        // 2) 收集摘要
                        try {
                            ONode node = ONode.ofJson(line);
                            String type = node.get("type").getString();
                            if ("text".equals(type) || "reason".equals(type)) {
                                String text = node.get("text").getString();
                                if (text != null && !text.isEmpty()) {
                                    if (resultBuilder.length() > 0) resultBuilder.append(" ");
                                    resultBuilder.append(text.trim());
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    })
                    .doOnComplete(() -> {
                        // 流结束时广播 [DONE]
                        sessionSink.emit(sessionId, "[DONE]");
                        String result = resultBuilder.toString().trim();
                        future.complete(result.isEmpty() ? "ok" : (result.length() > 200 ? result.substring(0, 200) + "..." : result));
                    })
                    .doOnError(e -> {
                        sessionSink.emit(sessionId, "[DONE]");
                        future.complete("error: " + e.getMessage());
                    })
                    .subscribe();
        } catch (Exception e) {
            LOG.error("Web loop task failed for session {}: {}", sessionId, e.getMessage());
            future.complete("error: " + e.getMessage());
        }

        return future;
    }

    /**
     * SSE 端点：前端通过此长连接订阅 Loop 定时任务及其他后台推送事件
     *
     * <p>前端使用 EventSource 建立连接后，所有 emit 到 sessionSink 的数据行
     * 都会以 SSE 格式实时推送到前端，复用 handleSSEData() 渲染。
     */
    @Get
    @Mapping("/chat/events")
    public SseEmitter chatEvents(String sessionId) throws Throwable {
        if (sessionId == null || sessionId.isEmpty()
                || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.complete();
            return emitter;
        }

        return sessionSink.createEmitter(sessionId);
    }

    /**
     * 对话主界面
     *
     * @return
     * @author oisin
     * @date 2026年3月14日
     */
    @Get
    @Mapping("/")
    public ModelAndView chat() {
        ModelAndView mv = new ModelAndView("chat.html");
        mv.put("appTitle", Solon.cfg().appTitle());
        mv.put("appVersion", AgentFlags.getVersion());
        mv.put("workspace", engine.getProps().getWorkspace());
        mv.put("workname", getLastSegment(engine.getProps().getWorkspace()));
        return mv;
    }

    private static String getLastSegment(String pathStr) {
        Path path = Paths.get(pathStr);
        // getFileName() 会返回路径中最后一级的文件或目录名
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    //---------------

    /**
     * 加载用户消息历史记录
     *
     * @author oisin
     * @date 2026年3月14日
     */
    @Get
    @Mapping("/chat/sessions")
    public Result<List<Map>> sessions() throws Exception {
        Path sessionsPath = Paths.get(engine.getProps().getWorkspace(), ".soloncode", "sessions").toAbsolutePath().normalize();
        File sessionsDir = sessionsPath.toFile();
        List<Map> data = new ArrayList<>();

        if (sessionsDir.exists() && sessionsDir.isDirectory()) {
            File[] dirs = sessionsDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("web-"));
            if (dirs != null) {
                // Sort by last modified, newest first
                Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());

                for (File dir : dirs) {
                    String sid = dir.getName();
                    File msgFile = new File(dir, sid + ".messages.ndjson");
                    if (!msgFile.exists()) continue;

                    String label = extractFirstUserMessage(msgFile);
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
     * 删除消息记录
     *
     * @author oisin
     * @date 2026年3月15日
     */
    @Post
    @Mapping("/chat/sessions/delete")
    public Result deleteSession(@Param("sessionId") String sessionId) throws Exception {
        // Security: prevent path traversal
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
     * 获取消息详细记录信息
     *
     * @author oisin
     * @date 2026年3月15日
     */
    @Get
    @Mapping("/chat/models")
    public Result<Map> models(@Param(value = "sessionId", required = false) String sessionId) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map> list = new ArrayList<>();

        for (ChatConfig config : engine.getProps().getModels()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("model", config.getNameOrModel());
            item.put("description", config.getDescriptionOrModel());
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

    @Post
    @Mapping("/chat/models/select")
    public Result models_select(@Param("sessionId") String sessionId, @Param("modelName") String modelName) throws Exception {
        AgentSession session = engine.getSession(sessionId);

        session.getContext().put(HarnessFlags.VAR_MODEL_SELECTED, modelName);

        session.updateSnapshot();

        return Result.succeed();
    }

    /**
     * 通过 ModelProviderFactory 从远程 API 获取可用模型列表
     */
    @Get
    @Mapping("/chat/models/fetch")
    public Result<List<Map>> fetchModels(@Param("apiUrl") String apiUrl, @Param("apiKey") String apiKey, @Param("provider") String provider) throws Exception {
        if (Assert.isEmpty(apiUrl)) {
            return Result.failure("apiUrl is required");
        }

        ModelProvider modelProvider = modelProviderFactory.getProvider(provider);
        String baseUrl = modelProvider.deriveBaseUrl(apiUrl);
        List<ModelInfo> models = modelProvider.fetchModels(baseUrl, null, apiKey);

        List<Map> list = new ArrayList<>();
        for (ModelInfo mi : models) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", mi.getId());
            item.put("object", mi.getObject());
            item.put("ownedBy", mi.getOwnedBy());

            ChatConfig config = new ChatConfig();
            config.setName(mi.getObject());
            config.setApiUrl(apiUrl);
            config.setApiKey(apiKey);
            config.setModel(mi.getObject());
            config.setUserAgent("Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; SolonCode/2.0; +https://solon.noear.org/)");
            engine.getProps().removeModel(mi.getObject());
            engine.getProps().addModel(config);
            list.add(item);
        }

        return Result.succeed(list);
    }

    /**
     * 动态添加模型配置
     */
    @Post
    @Mapping("/chat/models/add")
    public Result models_add(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());

        String apiUrl = root.get("apiUrl").getString();
        String apiKey = root.get("apiKey").getString();
        String model = root.get("model").getString();
        String provider = root.get("provider").getString();

        if (Assert.isEmpty(apiUrl) || Assert.isEmpty(model)) {
            return Result.failure("apiUrl and model are required");
        }

        String name = root.get("name").getString();
        if (Assert.isEmpty(name)) {
            name = model;
        }

        ChatConfig config = new ChatConfig();
        config.setName(name);
        config.setApiUrl(apiUrl);
        config.setApiKey(apiKey);
        config.setModel(model);
        config.setUserAgent("Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; SolonCode/2.0; +https://solon.noear.org/)");
//        if (Assert.isNotEmpty(provider)) {
//            config.setProvider(provider);
//        }

        // timeout
        String timeout = root.get("timeout").getString();
        if (Assert.isNotEmpty(timeout)) {
            config.setTimeout(java.time.Duration.parse(timeout));
        }

        // userAgent
        String userAgent = root.get("userAgent").getString();
        if (Assert.isNotEmpty(userAgent)) {
            config.setUserAgent(userAgent);
        }
        engine.getProps().removeModel(model);
        engine.getProps().addModel(config);

        LOG.info("[Web] Model added: {}", name);
        return Result.succeed(name);
    }

    /**
     * 动态移除模型配置
     */
    @Post
    @Mapping("/chat/models/remove")
    public Result models_remove(@Param("modelName") String modelName) throws Exception {
        if (Assert.isEmpty(modelName)) {
            return Result.failure("modelName is required");
        }

        // 不允许移除当前正在使用的主模型
        if (modelName.equals(engine.getMainModel().getNameOrModel())) {
            return Result.failure("Cannot remove the active main model");
        }

        engine.getProps().removeModel(modelName);

        LOG.info("[Web] Model removed: {}", modelName);
        return Result.succeed();
    }

    /**
     * 获取消息详细记录信息
     *
     * @author oisin
     * @date 2026年3月15日
     */
    @Get
    @Mapping("/chat/messages")
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
                        Map<String, String> item = new LinkedHashMap<>();
                        item.put("role", role);
                        item.put("content", content);
                        data.add(item);
                    }
                }
            }
        }

        return Result.succeed(data);
    }

    @Post
    @Mapping("/chat/interrupt")
    public Result interruptSession(@Param("sessionId") String sessionId) {
        // Security: prevent path traversal
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure();
        }

        AgentSession session = engine.getSession(sessionId);

        Disposable disposable = (Disposable) session.attrs().remove("disposable");
        if (disposable != null) {
            disposable.dispose();
        }
        session.addMessage(ChatMessage.ofAssistant("用户已取消任务."));
        LOG.info("用户已取消任务.");

        return Result.succeed();
    }

    private static final Set<String> IMAGE_EXTENSIONS = Utils.asSet(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg");

    private static boolean isImageAttachment(String ext, String attachmentsType) {
        return "image".equals(attachmentsType) && IMAGE_EXTENSIONS.contains(ext);
    }

    private static String extensionToMime(String ext) {
        switch (ext) {
            case ".jpg":
            case ".jpeg":
                return "image/jpeg";
            case ".png":
                return "image/png";
            case ".gif":
                return "image/gif";
            case ".webp":
                return "image/webp";
            case ".bmp":
                return "image/bmp";
            case ".svg":
                return "image/svg+xml";
            default:
                return "image/png";
        }
    }

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
     * 处理命令输入（所有结果统一走 SSE 格式返回，与前端 fetch+SSE 解析保持一致）
     */
    private void handleCommand(Context ctx, AgentSession session, ChatModel chatModel,
                               String sessionCwd, String input) throws Throwable {
        WebCommandDispatcher dispatcher = new WebCommandDispatcher(engine.getCommandRegistry());
        CommandResult result = dispatcher.dispatch(input, session, engine,
                (String prompt, String model) -> {
                    final ChatModel chatModelSelected;
                    if(model != null){
                        chatModelSelected = engine.getModelOrMain(model);
                    } else {
                        chatModelSelected = chatModel;
                    }

                    return streamBuilder.buildStreamFlux(session, chatModelSelected, sessionCwd, Prompt.of(prompt));
                });

        if (result == null) {
            // 不是有效命令，当作普通输入
            Prompt prompt = Prompt.of(input);
            ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);
            ctx.returnValue(streamBuilder.buildStreamFlux(session, chatModel, sessionCwd, prompt));
            return;
        }

        if (result.isAgentTask()) {
            // AGENT 类型命令：返回 SSE 流
            ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);
            ctx.returnValue(result.getAgentFlux());
        } else {
            // SYSTEM/CONFIG 类型命令：将输出包装为 SSE 格式返回
            ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);
            Flux<String> commandFlux = Flux.create(sink -> {
                try {
                    for (String line : result.getOutput()) {
                        ONode chunk = new ONode();
                        chunk.set("type", "command");
                        chunk.set("text", line);
                        sink.next(chunk.toJson());
                    }
                    sink.next("[DONE]");
                    sink.complete();
                } catch (Exception e) {
                    sink.error(e);
                }
            });
            ctx.returnValue(commandFlux);
        }
    }

    /**
     * 获取可用命令列表
     */
    @Get
    @Mapping("/chat/commands")
    public Result<List<Map>> commands() {
        List<Map> data = new ArrayList<>();
        for (Command cmd : engine.getCommandRegistry().all()) {
            // 跳过 CLI 专属命令（不在 Web 端展示）
            if (cmd.cliOnly()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", cmd.name());
            item.put("description", cmd.description());
            item.put("type", cmd.type().name());
            data.add(item);
        }
        return Result.succeed(data);
    }

    @Mapping("/chat/input")
    public void chat_input(Context ctx, String input, UploadedFile[] attachments, String attachmentsType, String model, String sessionId) throws Throwable {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = ctx.headerOrDefault("X-Session-Id", "web");
        }
        String sessionCwd = ctx.header("X-Session-Cwd");//工作区

        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            ctx.status(400);
            ctx.output("Invalid Session ID");
            return;
        }

        if (Assert.isNotEmpty(sessionCwd)) {
            //只有第一次传有效（后续的无效）
            if (sessionCwd.contains("..")) {
                ctx.status(400);
                ctx.output("Invalid Session Cwd");
                return;
            }
        }

        final AgentSession session = engine.getSession(sessionId);
        session.getContext().put(HarnessFlags.VAR_MODEL_SELECTED, model);
        final ChatModel chatModel = engine.getModelOrMain(model);

        // HITL approve/reject handling
        String hitlAction = ctx.param("hitlAction");
        if (Assert.isNotEmpty(hitlAction)) {
            HITLTask task = HITL.getPendingTask(session);
            if (task != null) {
                if ("approve".equals(hitlAction)) {
                    HITL.approve(session, task.getToolName());
                } else {
                    HITL.reject(session, task.getToolName());
                }
            }
            // Resume streaming after HITL decision
            ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);
            ctx.returnValue(streamBuilder.buildStreamFlux(session, chatModel, sessionCwd, null));
            return;
        }

        // Handle file upload (multipart/form-data)
        List<ImageBlock> imageBlocks = new ArrayList<>();
        List<String> fileAttachments = new ArrayList<>();

        if (attachments != null) {
            for (UploadedFile attachment : attachments) {
                String fileName = attachment.getName();
                if (fileName != null && !fileName.contains("..") && !fileName.contains("/") && !fileName.contains("\\")) {
                    String ext = "." + attachment.getExtension();

                    // All files: save to workspace first
                    Path savePath = Paths.get(engine.getProps().getWorkspace(), fileName).toAbsolutePath().normalize();
                    if (savePath.startsWith(Paths.get(engine.getProps().getWorkspace()).toAbsolutePath().normalize())) {
                        Files.copy(attachment.getContent(), savePath, StandardCopyOption.REPLACE_EXISTING);

                        if (isImageAttachment(ext, attachmentsType)) {
                            // Image: read back from saved file, convert to base64
                            byte[] bytes = Files.readAllBytes(savePath);
                            String base64 = Base64.getEncoder().encodeToString(bytes);
                            String mime = extensionToMime(ext);
                            imageBlocks.add(ImageBlock.ofBase64(base64, mime));
                        } else {
                            // Other: collect file names for prefix
                            fileAttachments.add(fileName);
                        }
                    }
                }
            }
        }

        // Build input text with file attachment prefix
        if (!fileAttachments.isEmpty()) {
            String filePrefix = fileAttachments.stream()
                    .map(f -> "[附件: " + f + "]")
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (input == null || input.isEmpty()) {
                input = filePrefix + "\n请帮我处理这些附件";
            } else {
                input = filePrefix + "\n" + input;
            }
        }

        if (Assert.isNotEmpty(input) || !imageBlocks.isEmpty()) {
            if (input == null || input.isEmpty()) {
                input = imageBlocks.size() > 1 ? "请描述这些图片" : "请描述这张图片";
            }

            // 命令分发：检测 / 前缀
            if (input.startsWith("/") && imageBlocks.isEmpty()) {
                handleCommand(ctx, session, chatModel, sessionCwd, input);
                return;
            }

            Prompt prompt;
            if (!imageBlocks.isEmpty()) {
                Contents contents = new Contents();
                contents.addBlock(TextBlock.of(input));
                for (ImageBlock block : imageBlocks) {
                    contents.addBlock(block);
                }
                prompt = Prompt.of(new UserMessage(contents));
            } else {
                prompt = Prompt.of(input);
            }

            ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);
            ctx.returnValue(streamBuilder.buildStreamFlux(session, chatModel, sessionCwd, prompt));
        }
    }
}