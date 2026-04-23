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
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ActionEndChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.react.task.ThoughtChunk;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskSkill;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.core.AgentFlags;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ModelAndView;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.core.util.MimeType;
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
import java.time.Duration;
import java.util.*;

/**
 * Web Chat Controller
 * @author oisin 2026-3-13
 * @author noear 2026-4-18
 */
public class WebController {
    private static final Logger LOG = LoggerFactory.getLogger(WebController.class);

    private final HarnessEngine engine;

    public WebController(HarnessEngine engine) {
        this.engine = engine;
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
            String selected = session.getContext().getOrDefault(AgentFlags.VAR_MODEL_SELECTED,
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

        session.getContext().put(AgentFlags.VAR_MODEL_SELECTED, modelName);

        session.updateSnapshot();

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
    private static boolean isImageExtension(String ext) {
        return IMAGE_EXTENSIONS.contains(ext);
    }

    private static String extensionToMime(String ext) {
        switch (ext) {
            case ".jpg": case ".jpeg": return "image/jpeg";
            case ".png": return "image/png";
            case ".gif": return "image/gif";
            case ".webp": return "image/webp";
            case ".bmp": return "image/bmp";
            case ".svg": return "image/svg+xml";
            default: return "image/png";
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

    @Mapping("/chat/input")
    public void chat_input(Context ctx, String input, UploadedFile attachment, String model, String sessionId) throws Throwable {
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
        session.getContext().put(AgentFlags.VAR_MODEL_SELECTED, model);
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
            ctx.returnValue(buildStreamFlux(session, chatModel, sessionCwd, null));
            return;
        }

        // Handle file upload (multipart/form-data)
        String imageBase64 = null;
        String imageMime = null;

        if (attachment != null) {
            String fileName = attachment.getName();
            if (fileName != null && !fileName.contains("..") && !fileName.contains("/") && !fileName.contains("\\")) {
                String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".")).toLowerCase() : "";

                // All files: save to workspace first
                Path savePath = Paths.get(engine.getProps().getWorkspace(), fileName).toAbsolutePath().normalize();
                if (savePath.startsWith(Paths.get(engine.getProps().getWorkspace()).toAbsolutePath().normalize())) {
                    Files.copy(attachment.getContent(), savePath, StandardCopyOption.REPLACE_EXISTING);

                    if (isImageExtension(ext)) {
                        // Image: read back from saved file, convert to base64
                        byte[] bytes = Files.readAllBytes(savePath);
                        imageBase64 = Base64.getEncoder().encodeToString(bytes);
                        imageMime = extensionToMime(ext);
                    } else {
                        // Other: just reference the saved file
                        input = "[附件: " + fileName + "]\n" + (input != null && !input.isEmpty() ? input : "请帮我处理这个附件");
                    }
                }
            }
        }

        if (Assert.isNotEmpty(input) || Assert.isNotEmpty(imageBase64)) {
            if (input == null || input.isEmpty()) {
                input = "请描述这张图片";
            }
            ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);
            ctx.returnValue(buildStreamFlux(session, chatModel, sessionCwd, input, imageBase64, imageMime));
        }
    }

    private Flux<String> buildStreamFlux(AgentSession session, ChatModel chatModel, String sessionCwd, String input) {
        return buildStreamFlux(session, chatModel, sessionCwd, input, null, null);
    }

    private Flux<String> buildStreamFlux(AgentSession session, ChatModel chatModel, String sessionCwd, String input, String imageBase64, String imageMime) {
        final Prompt prompt;
        if("/resume".equals(input)){
            prompt = Prompt.of().attrPut("start_time", System.currentTimeMillis());
        } else if (Assert.isNotEmpty(imageBase64)) {
            // Multimodal: text + image
            ChatMessage userMsg = ChatMessage.ofUser(input, org.noear.solon.ai.chat.content.ImageBlock.ofBase64(imageBase64, imageMime != null ? imageMime : "image/png"));
            prompt = Prompt.of().addMessage(userMsg).attrPut("start_time", System.currentTimeMillis());
        } else {
            prompt = Prompt.of(input).attrPut("start_time", System.currentTimeMillis());
        }


        return engine.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.chatModel(chatModel);

                    if (Assert.isNotEmpty(sessionCwd)) {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, sessionCwd);
                    }
                })
                .stream()
                .map(chunk -> {
                    if (chunk instanceof ReasonChunk) {
                        return onReasonChunk((ReasonChunk) chunk);
                    } else if (chunk instanceof ThoughtChunk) {
                        return onThoughtChunk((ThoughtChunk) chunk);
                    } else if (chunk instanceof ActionEndChunk) {
                        return onActionEndChunk((ActionEndChunk) chunk);
                    } else if (chunk instanceof ReActChunk) {
                        return onFinalChunk((ReActChunk) chunk);
                    }

                    return "";
                })
                .filter(Assert::isNotEmpty)
                .doOnSubscribe(subscription -> {
                    // 将 Subscription 包装为 Disposable
                    Disposable disposable = subscription::cancel;

                    // 在订阅开始时，将 disposable 存入 session
                    session.attrs().put("disposable", disposable);
                })
                .onErrorResume(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    String message = new ONode().set("type", "error")
                            .set("text", e.getMessage())
                            .toJson();

                    return Flux.just(message);
                })
                .concatWith(Flux.defer(() -> {
                    // Check HITL state after stream completes
                    if (HITL.isHitl(session)) {
                        HITLTask task = HITL.getPendingTask(session);
                        if (task != null) {
                            String command = "bash".equals(task.getToolName())
                                    ? String.valueOf(task.getArgs().get("command"))
                                    : null;
                            String hitlMsg = new ONode().set("type", "hitl")
                                    .set("toolName", task.getToolName())
                                    .set("command", command)
                                    .toJson();
                            return Flux.just(hitlMsg, "[DONE]");
                        }
                    }
                    return Flux.just("[DONE]");
                }))
                .doFinally(signal -> {
                    // 流结束或被取消后，清理掉引用，避免内存泄漏
                    session.attrs().remove("disposable");
                });
    }

    private String onReasonChunk(ReasonChunk reason) {
        if (!reason.isToolCalls() && reason.hasContent()) {
            if (reason.getMessage().isThinking()) {
                return new ONode().set("type", "reason")
                        .set("text", reason.getContent())
                        .toJson();
            } else {
                return new ONode().set("type", "text")
                        .set("text", reason.getContent())
                        .toJson();
            }
        }

        return "";
    }

    private String onThoughtChunk(ThoughtChunk thought) {
        if (thought.hasMeta(TaskSkill.TOOL_MULTITASK)) {
            // 仅在多任务并行且有内容时输出
            String content = thought.getAssistantMessage().getResultContent();
            if (Assert.isNotEmpty(content)) {
                return new ONode().set("type", "text")
                        .set("text", content)
                        .toJson();
            }
        }

        return "";
    }

    private String onActionEndChunk(ActionEndChunk action) {
        if (Assert.isNotEmpty(action.getToolName())) {
            if (TaskSkill.TOOL_MULTITASK.equals(action.getToolName()) ||
                    TaskSkill.TOOL_TASK.equals(action.getToolName())) {
                return "";
            }

            ONode oNode = new ONode().set("type", "action")
                    .set("text", action.getContent());

            if (Assert.isNotEmpty(action.getToolName())) {
                if (engine.getName().equals(action.getAgentName())) {
                    oNode.set("toolName", action.getToolName());
                } else {
                    oNode.set("toolName", action.getAgentName() + "/" + action.getToolName());
                }
                oNode.set("args", action.getArgs());
            }

            return oNode.toJson();
        }

        return "";
    }

    private String onFinalChunk(ReActChunk react) {
        StringBuilder buf = new StringBuilder();

        Long start_time = react.getTrace().getOriginalPrompt().attrAs("start_time");


        buf.append(" (");

        buf.append(react.getTrace().getOptions().getChatModel().getNameOrModel());

        if (react.getTrace().getMetrics() != null) {
            if (buf.length() > 2) {
                buf.append(", ");
            }

            buf.append(react.getTrace().getMetrics().getTotalTokens()).append(" tokens");
        }

        if (start_time != null) {
            if (buf.length() > 2) {
                buf.append(", ");
            }

            long seconds = Duration.ofMillis(System.currentTimeMillis() - start_time).getSeconds();
            buf.append(seconds).append(" seconds");
        }

        buf.append(")");

        return new ONode().set("type", "text")
                .set("text", buf.toString())
                .toJson();
    }
}