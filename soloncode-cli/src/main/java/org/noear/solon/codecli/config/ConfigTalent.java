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
        return "运行时动态添加 LLM 模型、MCP 服务、OpenAPI 源，添加后立即生效并持久化。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "根据用户提供的信息（如密钥、地址等）由 AI 调用工具完成配置。" +
                "若信息不能直接映射到工具参数，应先通过网络搜索等方式查找能实现用户目的的工具或服务，" +
                "再转换为完整配置；信息不足时先向用户确认。";
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

    @ToolMapping(name = "add_mcp_server",
            description = "添加一个新的 MCP 服务，使其工具可被调用。添加后立即生效并持久化。" +
                    "不同 transport 的必输参数说明：" +
                    "1) stdio 模式：必须输入 command（启动命令，如 'npx'、'uvx'、'node' 等）；args（命令参数列表）可选；env（环境变量）可选。" +
                    "2) streamable 或 sse 模式：必须输入 url（服务地址，如 'http://localhost:8080/mcp'）；headers（自定义请求头）可选。" +
                    "三种模式互斥，只能选一种 transport。")
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

        // ---- transport 参数校验 ----
        if (transport == null || transport.isEmpty()) {
            return "ERROR: transport 不能为空，必须为 stdio、sse 或 streamable 三者之一。请补充 transport 参数后重试。";
        }

        String t = transport.toLowerCase();

        if ("stdio".equals(t)) {
            if (command == null || command.isEmpty()) {
                return "ERROR: stdio 模式必须提供 command 参数（启动命令，如 'npx'、'uvx'、'node' 等）。请补充 command 后重试。";
            }
            if (url != null && !url.isEmpty()) {
                return "ERROR: stdio 模式不支持 url 参数（url 仅用于 sse/streamable 模式）。请移除 url 后重试。";
            }
        } else if ("sse".equals(t) || "streamable".equals(t)) {
            if (url == null || url.isEmpty()) {
                return "ERROR: " + transport + " 模式必须提供 url 参数（服务地址，如 'http://localhost:8080/mcp'）。请补充 url 后重试。";
            }
            if (command != null && !command.isEmpty()) {
                return "ERROR: " + transport + " 模式不支持 command 参数（command 仅用于 stdio 模式）。请移除 command 后重试。";
            }
        } else {
            return "ERROR: 不支持的 transport 类型 '" + transport + "'。仅支持 stdio、sse、streamable。请修正后重试。";
        }

        // ---- 构建并注册 ----
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

        return "OK: MCP 服务 '" + name + "' 已添加（transport=" + transport + "）";
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
