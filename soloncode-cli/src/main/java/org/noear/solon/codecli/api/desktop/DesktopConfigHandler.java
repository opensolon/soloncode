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
package org.noear.solon.codecli.api.desktop;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatConfigReadonly;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.net.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 桌面端 config 消息处理器：运行时模型配置热更新 + YAML 持久化。
 *
 * <p>从原 {@code WsGate} 中拆出 {@code handleConfigMessage / saveConfigToFile /
 * escapeYaml}。字段级合并、引擎重建（removeModel→addModel→refreshMainAgent）、
 * {@code ~/.soloncode/chat-model.yml} 的写入语义与响应结构均逐字保留。</p>
 *
 * @author bai
 * @since 3.9.1
 */
final class DesktopConfigHandler {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopConfigHandler.class);

    private final HarnessEngine engine;

    DesktopConfigHandler(HarnessEngine engine) {
        this.engine = engine;
    }

    /**
     * 处理前端推送的配置变更
     */
    void handle(WebSocket socket, ONode root) {
        try {
            ONode chatModelNode = root.get("chatModel");
            if (chatModelNode != null && !chatModelNode.isNull()) {
                String apiUrl = chatModelNode.get("apiUrl") != null ? chatModelNode.get("apiUrl").getString() : null;
                String apiKey = chatModelNode.get("apiKey") != null ? chatModelNode.get("apiKey").getString() : null;
                String model = chatModelNode.get("model") != null ? chatModelNode.get("model").getString() : null;
                String provider = chatModelNode.get("provider") != null ? chatModelNode.get("provider").getString() : null;
                ChatConfigReadonly currentConfig = engine.getMainModel() == null ? null : engine.getMainModel().getConfig();
                String existApiUrl = currentConfig == null ? null : currentConfig.getApiUrl();
                String existApiKey = currentConfig == null ? null : currentConfig.getApiKey();
                String existModel = currentConfig == null ? null : currentConfig.getNameOrModel();
                String existProvider = currentConfig == null ? null : currentConfig.getStandardOrProvider();
                String finalApiUrlInput = apiUrl != null ? apiUrl : existApiUrl;
                String normalizedProvider = provider != null ? provider : existProvider;
                String normalizedApiUrl = finalApiUrlInput;
                String finalApiKey = apiKey != null ? apiKey : existApiKey;
                String finalModel = model != null ? model : existModel;

                if (apiUrl != null || apiKey != null || model != null || provider != null) {
                    // 更新 AgentProperties 的 chatModel 配置
                    ChatConfig chatConfig = new ChatConfig();
                    chatConfig.setApiUrl(normalizedApiUrl);
                    chatConfig.setApiKey(finalApiKey);
                    chatConfig.setModel(finalModel);
                    chatConfig.setStandard(normalizedProvider);

                    // 重建 ChatModel 并注入 kernel
                    engine.removeModel(chatConfig.getNameOrModel());
                    engine.addModel(chatConfig);
                    engine.refreshMainAgent();

                    LOG.info("[WS] Config updated: model={}, provider={}", finalModel, normalizedProvider);

                    // 持久化到 YAML 文件
                    saveConfigToFile(normalizedApiUrl, finalApiKey, finalModel, normalizedProvider);

                    socket.send(new ONode()
                            .set("type", "config")
                            .set("status", "ok")
                            .set("model", finalModel)
                            .toJson());
                }
            }
        } catch (Exception e) {
            LOG.error("[WS] Config update failed", e);
            socket.send(new ONode()
                    .set("type", "config")
                    .set("status", "error")
                    .set("text", e.getMessage())
                    .toJson());
        }
    }

    /**
     * 将 chatModel 配置持久化到 YAML 文件（~/.soloncode/chat-model.yml）
     */
    private void saveConfigToFile(String apiUrl, String apiKey, String model, String provider) {
        try {
            String home = System.getProperty("user.home");
            Path configDir = Paths.get(home, ".soloncode");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("chat-model.yml");

            // 读取已有配置，保留未更新的字段
            ChatConfigReadonly currentConfig = engine.getMainModel() == null ? null : engine.getMainModel().getConfig();
            String existApiUrl = currentConfig != null ? currentConfig.getApiUrl() : null;
            String existApiKey = currentConfig != null ? currentConfig.getApiKey() : null;
            String existModel = currentConfig != null ? currentConfig.getNameOrModel() : null;
            String existProvider = currentConfig != null ? currentConfig.getStandardOrProvider() : null;

            String finalApiUrl = apiUrl != null ? apiUrl : existApiUrl;
            String finalApiKey = apiKey != null ? apiKey : existApiKey;
            String finalModel = model != null ? model : existModel;
            String finalProvider = provider != null ? provider : existProvider;

            StringBuilder yaml = new StringBuilder();
            yaml.append("soloncode:\n");
            yaml.append("  chatModel:\n");
            if (finalApiUrl != null) yaml.append("    apiUrl: \"").append(escapeYaml(finalApiUrl)).append("\"\n");
            if (finalApiKey != null) yaml.append("    apiKey: \"").append(escapeYaml(finalApiKey)).append("\"\n");
            if (finalModel != null) yaml.append("    model: \"").append(escapeYaml(finalModel)).append("\"\n");
            if (org.noear.solon.core.util.Assert.isNotEmpty(finalProvider)) yaml.append("    provider: \"").append(escapeYaml(finalProvider)).append("\"\n");

            Files.write(configFile, yaml.toString().getBytes(StandardCharsets.UTF_8));
            LOG.info("[WS] Config persisted to: {}", configFile);
        } catch (Exception e) {
            LOG.error("[WS] Failed to persist config to YAML", e);
        }
    }

    private String escapeYaml(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
