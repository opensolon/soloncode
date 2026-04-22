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
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.core.AgentFlags;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.core.handle.ModelAndView;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Web Chat Controller
 * @author oisin 2026-3-13
 * @author noear 2026-4-18
 */
@Controller
public class WebController {
    private static final Logger LOG = LoggerFactory.getLogger(WebController.class);

    @Inject
    HarnessEngine agentRuntime;

    @Inject
    private AgentProperties agentProps;

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
        mv.put("sseEndpoint", agentProps.getWebEndpoint());
        mv.put("workspace", agentProps.getWorkspace());
        mv.put("workname", getLastSegment(agentProps.getWorkspace()));
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
        Path sessionsPath = Paths.get(agentProps.getWorkspace(), ".soloncode", "sessions").toAbsolutePath().normalize();
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

        Path sessionPath = Paths.get(agentProps.getWorkspace(), ".soloncode", "sessions", sessionId).toAbsolutePath().normalize();
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

        for (ChatConfig config : agentProps.getModels()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("model", config.getModel());
            item.put("description", config.getDescriptionOrModel());
            list.add(item);
        }
        data.put("list", list);

        if (Assert.isNotEmpty(sessionId)) {
            AgentSession session = agentRuntime.getSession(sessionId);
            String selected = session.getContext().getOrDefault(AgentFlags.VAR_MODEL_SELECTED, agentRuntime.getMainModel().getModel());

            data.put("selected", selected);
        } else {
            data.put("selected", agentRuntime.getMainModel().getModel());
        }

        return Result.succeed(data);
    }

    @Post
    @Mapping("/chat/models/select")
    public Result models_select(@Param("sessionId") String sessionId, @Param("modelName") String modelName) throws Exception {
        AgentSession session = agentRuntime.getSession(sessionId);

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
        Path sessionsPath = Paths.get(agentProps.getWorkspace(), ".soloncode", "sessions", sessionId).toAbsolutePath().normalize();
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

        AgentSession session = agentRuntime.getSession(sessionId);

        Disposable disposable = (Disposable) session.attrs().remove("disposable");
        if (disposable != null) {
            disposable.dispose();
        }
        session.addMessage(ChatMessage.ofAssistant("用户已取消任务."));
        LOG.info("用户已取消任务.");

        return Result.succeed();
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
}