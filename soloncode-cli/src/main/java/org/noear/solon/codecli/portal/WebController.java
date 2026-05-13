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
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessFlags;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.channel.dingtalk.DingTalkLink;
import org.noear.solon.codecli.channel.feishu.FeishuLink;
import org.noear.solon.codecli.channel.wechat.WeChatClient;
import org.noear.solon.codecli.channel.wechat.WeChatLink;
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

/**
 * Web Chat Controller
 *
 * <p>输入通过 HTTP POST 接收，AI 处理结果通过 WebGate 的 WebSocket 推送到前端。</p>
 *
 * @author oisin 2026-3-13
 * @author noear 2026-4-18
 */
public class WebController {
    private static final Logger LOG = LoggerFactory.getLogger(WebController.class);

    private final HarnessEngine engine;
    private final WebGate webGate;
    private final LoopScheduler loopScheduler;

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
     * 入口：重定向到静态首页 index.html。
     */
    @Get
    @Mapping("/")
    public void index(Context ctx) throws Throwable {
        ctx.forward("/web.html");
    }

    /**
     * 页面元信息：由静态首页（/index.html）启动时 fetch 一次，用于回填标题与侧栏。
     */
    @Get
    @Mapping("/chat/meta")
    public Result<Map> meta() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appTitle", Solon.cfg().appTitle());
        data.put("appVersion", AgentFlags.getVersion());
        data.put("workspace", engine.getProps().getWorkspace());
        data.put("workname", getLastSegment(engine.getProps().getWorkspace()));
        return Result.succeed(data);
    }

    private static String getLastSegment(String pathStr) {
        Path path = Paths.get(pathStr);
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    //---------------

    /**
     * 加载用户消息历史记录
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
     */
    @Post
    @Mapping("/chat/sessions/delete")
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
     * 获取消息详细记录信息
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

    @Post
    @Mapping("/chat/interrupt")
    public Result interruptSession(@Param("sessionId") String sessionId) {
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure();
        }

        webGate.interruptSession(sessionId);

        return Result.succeed();
    }

    /**
     * 获取可用命令列表
     */
    @Get
    @Mapping("/chat/commands")
    public Result<List<Map>> commands() {
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

        return Result.succeed(data);
    }

    /**
     * 聊天输入：解析参数后路由到 WebGate 处理
     */
    @Mapping("/chat/input")
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



    // ==================== 工具方法 ====================

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
}