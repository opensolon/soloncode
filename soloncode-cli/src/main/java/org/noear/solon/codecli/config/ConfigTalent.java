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
package org.noear.solon.codecli.config;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.codecli.config.entity.ApiSourceDo;
import org.noear.solon.codecli.config.entity.McpServerDo;
import org.noear.solon.codecli.config.entity.ModelDo;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 配置管理 Talent
 * <p>允许 AI 在运行时动态添加 LLM 模型、MCP 服务、OpenAPI 源，并与 AgentSettings 同步持久化。</p>
 *
 * @author noear 2026/6/8 created
 */
public class ConfigTalent extends AbsTalent {
    private final HarnessEngine engine;
    private final AgentSettings settings;

    public ConfigTalent(HarnessEngine engine, AgentSettings settings) {
        this.engine = engine;
        this.settings = settings;
    }

    @Override
    public String description() {
        return "配置管理专家：允许在运行时动态添加 LLM 模型、MCP 服务、OpenAPI 源。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "## 配置管理工具\n" +
                "需要用户提供关键信息（如密钥、地址等），由 AI 调用工具完成配置添加。\n" +
                "添加后配置会同时注册到引擎（立即生效）并持久化。\n";
    }

    // ==================== add_model ====================

    @ToolMapping(name = "add_model", description = "添加一个新的 LLM 模型配置，使其可用于对话。添加后立即生效并持久化。")
    public String addModel(
            @Param(name = "name", description = "模型名称标识", required = false) String name,
            @Param(name = "apiUrl", description = "API 服务地址") String apiUrl,
            @Param(name = "apiKey", description = "API 密钥") String apiKey,
            @Param(name = "standard", description = "接口规范（可选：openai、ollama、anthropic）", required = false) String standard,
            @Param(name = "model", description = "模型 ID（如 gpt-4o、deepseek-chat 等）") String model,
            @Param(name = "headers", description = "自定义请求头", required = false) Map<String, String> headers,
            @Param(name = "timeout", description = "超时秒数（可选，默认 120）", required = false) String timeout) {

        ModelDo modelDo = new ModelDo();
        modelDo.setName(name);
        modelDo.setApiUrl(apiUrl);
        modelDo.setApiKey(apiKey);
        modelDo.setStandard(standard);
        modelDo.setModel(model);
        if (headers != null && !headers.isEmpty()) {
            modelDo.setHeaders(headers);
        }
        if (timeout != null && !timeout.isEmpty()) {
            modelDo.setTimeout(Duration.ofSeconds(Long.parseLong(timeout)));
        }

        // 1) 注册到引擎（运行时生效）
        engine.addModel(modelDo);

        // 2) 同步到 settings（持久化）
        settings.getModels().add(modelDo);
        settings.saveToFile();

        return "OK: 模型 '" + name + "' 已添加";
    }

    // ==================== add_mcp_server ====================

    @ToolMapping(name = "add_mcp_server", description = "添加一个新的 MCP 服务，使其工具可被调用。添加后立即生效并持久化。")
    public String addMcpServer(
            @Param(name = "name", description = "服务名称标识") String name,
            @Param(name = "transport", description = "传输协议：sse、streamable、stdio（三选一）") String transport,
            @Param(name = "url", description = "sse、streamable 模式的服务地址", required = false) String url,
            @Param(name = "headers", description = "sse、streamable 模式自定义请求头", required = false) Map<String, String> headers,
            @Param(name = "command", description = "stdio 模式的启动命令", required = false) String command,
            @Param(name = "args", description = "stdio 模式的命令参数", required = false) List<String> args,
            @Param(name = "env", description = "stdio 模式的环境变量", required = false) Map<String, String> env,
            @Param(name = "disallowedTools", description = "不允许用的工具黑名单", required = false) List<String> disallowedTools,
            @Param(name = "timeout", description = "超时秒数", required = false) String timeout) {

        McpServerDo mcpDo = new McpServerDo();
        mcpDo.setTransport(transport);
        if (url != null) mcpDo.setUrl(url);
        if (headers != null && !headers.isEmpty()) mcpDo.setHeaders(headers);
        if (command != null) mcpDo.setCommand(command);
        if (args != null) mcpDo.setArgs(args);
        if (env != null) mcpDo.setEnv(env);
        if (disallowedTools != null) mcpDo.setDisallowedTools(disallowedTools);
        if (timeout != null && !timeout.isEmpty()) mcpDo.setTimeout(Duration.ofSeconds(Long.parseLong(timeout)));

        // 1) 注册到引擎（运行时生效）
        engine.addMcpServer(name, mcpDo);

        // 2) 同步到 settings（持久化）
        settings.getMcpServers().put(name, mcpDo);
        settings.saveToFile();

        return "OK: MCP 服务 '" + name + "' 已添加";
    }

    // ==================== add_api_server ====================

    @ToolMapping(name = "add_api_server", description = "添加一个新的 OpenAPI 源，使其接口可被调用。添加后立即生效并持久化。")
    public String addApiServer(
            @Param(name = "docUrl", description = "OpenAPI 文档地址") String docUrl,
            @Param(name = "apiBaseUrl", description = "API 基础路径", required = false) String apiBaseUrl,
            @Param(name = "headers", description = "自定义请求头", required = false) Map<String, String> headers,
            @Param(name = "disallowedTools", description = "不允许用的工具黑名单", required = false) List<String> disallowedTools,
            @Param(name = "timeout", description = "超时秒数", required = false) String timeout) {

        ApiSourceDo apiDo = new ApiSourceDo();
        apiDo.setDocUrl(docUrl);
        if (apiBaseUrl != null) apiDo.setApiBaseUrl(apiBaseUrl);
        if (headers != null && !headers.isEmpty()) apiDo.setHeaders(headers);
        if (disallowedTools != null) apiDo.setDisallowedTools(disallowedTools);
        if (timeout != null && !timeout.isEmpty()) apiDo.setTimeout(Duration.ofSeconds(Long.parseLong(timeout)));

        // 1) 注册到引擎（运行时生效）
        engine.addApiServer(apiDo);

        // 2) 同步到 settings（持久化）
        settings.getApiServers().put(docUrl, apiDo);
        settings.saveToFile();

        return "OK: API 源 '" + docUrl + "' 已添加";
    }
}
