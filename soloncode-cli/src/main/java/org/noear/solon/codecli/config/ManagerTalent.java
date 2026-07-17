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
import org.noear.solon.annotation.Param;

import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.command.builtin.LoopTask;
import org.noear.solon.codecli.config.entity.ApiSourceDo;
import org.noear.solon.codecli.config.entity.McpServerDo;
import org.noear.solon.codecli.config.entity.ModelDo;
import org.noear.solon.core.util.Assert;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 配置管理 Talent
 * <p>允许 AI 在运行时动态添加 LLM 模型、MCP 服务、OpenAPI 源，并与 AgentSettings 同步持久化。</p>
 *
 * @author noear 2026/6/8 created
 */
public class ManagerTalent extends AbsTalent {
    private final HarnessEngine engine;
    private final AgentSettings settings;
    private final LoopTaskOperations loopTasks;

    public ManagerTalent(HarnessEngine engine, AgentSettings settings, LoopScheduler loopScheduler) {
        this(engine, settings, new LoopTaskOperations() {
            @Override
            public LoopTask schedule(String sessionId, LoopTask task) {
                return loopScheduler.schedule(sessionId, task);
            }

            @Override
            public LoopTask getTaskById(String sessionId, String taskId) {
                return loopScheduler.getTaskById(sessionId, taskId);
            }

            @Override
            public void remove(String sessionId, LoopTask task) {
                loopScheduler.remove(sessionId, task);
            }
        });
    }

    ManagerTalent(HarnessEngine engine, AgentSettings settings, LoopTaskOperations loopTasks) {
        this.engine = engine;
        this.settings = settings;
        this.loopTasks = loopTasks;
    }


    // ==================== add_model ====================

    @ToolMapping(name = "add_model", description = "添加 LLM 模型配置。添加后立即生效并持久化。参数应由用户提供，若有缺失应主动向用户确认后再调用。")
    public String addModel(
            @Param(name = "name", description = "模型名称标识（可选；不传则自动取 model 值）", required = false) String name,
            @Param(name = "apiUrl", description = "API 服务地址（如 https://api.openai.com/v1）") String apiUrl,
            @Param(name = "apiKey", description = "API 密钥") String apiKey,
            @Param(name = "standard", description = "接口规范：openai、ollama、anthropic（不传则自动推断）", required = false) String standard,
            @Param(name = "model", description = "模型 ID（如 gpt-5.5、deepseek-v4-flash）") String model,
            @Param(name = "headers", description = "自定义请求头（如 Authorization: Bearer xxx）", required = false) Map<String, String> headers,
            @Param(name = "timeout", description = "超时秒数，默认 120", required = false) String timeout) {

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
        settings.getModels().put(modelDo.getNameOrModel(), modelDo);
        settings.saveToFile();

        return "OK: 模型 '" + name + "' 已添加";
    }

    // ==================== add_mcp_server ====================

    @ToolMapping(name = "add_mcp_server",
            description = "添加 MCP 服务集成第三方工具。添加后立即生效并持久化。" +
                    "传输协议三选一，参数按协议而定：" +
                    "- stdio：必填 command（如 npx/uvx/node）+ 可选 args/env。若依赖远程包，须先确认已安装。" +
                    "- sse/streamable：必填 url（如 http://localhost:8080/mcp）+ 可选 headers。" +
                    "三种协议互斥不可混用。timeout 默认 120s。" +
                    "参数不完整时应主动向用户确认后再调用。")
    public String addMcpServer(
            @Param(name = "name", description = "服务名称，需全局唯一") String name,
            @Param(name = "transport", description = "传输协议：stdio / sse / streamable（三选一）") String transport,
            @Param(name = "url", description = "服务地址（仅 sse/streamable 模式必填，如 http://localhost:8080/mcp）", required = false) String url,
            @Param(name = "headers", description = "自定义请求头（仅 sse/streamable 模式）", required = false) Map<String, String> headers,
            @Param(name = "command", description = "启动命令（仅 stdio 模式必填，如 npx/uvx/node）", required = false) String command,
            @Param(name = "args", description = "命令参数（仅 stdio 模式）", required = false) List<String> args,
            @Param(name = "env", description = "环境变量（仅 stdio 模式）", required = false) Map<String, String> env,
            @Param(name = "timeout", description = "超时秒数，默认 120", required = false) String timeout) {

        // ---- transport 参数校验 ----
        if (transport == null || transport.isEmpty()) {
            return "ERROR: transport 不能为空，必须为 stdio、sse 或 streamable 三者之一。请补充 transport 参数后重试。";
        }

        String t = transport.toLowerCase();

        if ("stdio".equals(t)) {
            if (Assert.isEmpty(command)) {
                return "ERROR: stdio 模式必须提供 command 参数（启动命令，如 'npx'、'uvx'、'node' 等）。请补充 command 后重试。";
            }
            if (Assert.isNotEmpty(url)) {
                return "ERROR: stdio 模式不支持 url 参数（url 仅用于 sse/streamable 模式）。请移除 url 后重试。";
            }
        } else if ("sse".equals(t) || "streamable".equals(t)) {
            if (Assert.isEmpty(url)) {
                return "ERROR: " + transport + " 模式必须提供 url 参数（服务地址，如 'http://localhost:8080/mcp'）。请补充 url 后重试。";
            }
            if (Assert.isNotEmpty(command)) {
                return "ERROR: " + transport + " 模式不支持 command 参数（command 仅用于 stdio 模式）。请移除 command 后重试。";
            }
        } else {
            return "ERROR: 不支持的 transport 类型 '" + transport + "'。仅支持 stdio、sse、streamable。请修正后重试。";
        }

        // ---- 构建并注册 ----
        McpServerDo mcpDo = new McpServerDo();
        mcpDo.setType(transport);
        if (url != null) mcpDo.setUrl(url);
        if (headers != null && !headers.isEmpty()) mcpDo.setHeaders(headers);
        if (command != null) mcpDo.setCommand(command);
        if (args != null) mcpDo.setArgs(args);
        if (env != null) mcpDo.setEnv(env);
        if (timeout != null && !timeout.isEmpty()) mcpDo.setTimeout(Duration.ofSeconds(Long.parseLong(timeout)));

        // 1) 注册到引擎（运行时生效）
        engine.addMcpServer(name, mcpDo);

        // 2) 同步到 settings（持久化）
        settings.getMcpServers().put(name, mcpDo);
        settings.saveToFile();

        return "OK: MCP 服务 '" + name + "' 已添加（transport=" + transport + "）";
    }

    // ==================== add_api_server ====================

    @ToolMapping(name = "add_api_server", description = "添加 OpenAPI 源导入外部 HTTP API。添加后立即生效并持久化。docUrl 应指向 OpenAPI 规范文档（JSON/YAML）；若用户未提供，主动询问后再调用。")
    public String addApiServer(
            @Param(name = "docUrl", description = "OpenAPI 规范文档地址（JSON/YAML 格式，如 https://api.example.com/openapi.json）") String docUrl,
            @Param(name = "apiBaseUrl", description = "API 基础路径（可选，覆盖规范中的 server 地址）", required = false) String apiBaseUrl,
            @Param(name = "headers", description = "自定义请求头（如 API-Key: xxx）", required = false) Map<String, String> headers,
            @Param(name = "disallowedTools", description = "禁用的工具名列表（不在列表中暴露）", required = false) List<String> disallowedTools,
            @Param(name = "timeout", description = "超时秒数，默认 120", required = false) String timeout) {

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

    // ==================== loop task ====================

    @ToolMapping(name = "add_loop_task",
            description = "新增当前会话的定时任务（或循环任务）。支持固定分钟间隔或 cron 表达式；" +
                    "未提供 intervalMinutes 时默认每 5 分钟执行。创建成功后返回 taskId，后续可用它删除任务。")
    public String addLoopTask(
            @Param(name = "prompt", description = "任务触发后交给 AI 执行的提示词") String prompt,
            @Param(name = "intervalMinutes", description = "固定执行间隔（分钟），默认 5；提供 cron 时以 cron 为准", required = false) Integer intervalMinutes,
            @Param(name = "cron", description = "标准 7 位 cron 表达式（可选，格式：秒 分 时 日 月 周 年）；提供后使用 cron 调度，例如 0 */5 * * * ? *", required = false) String cron,
            @Param(name = "type", description = "任务类型：HEARTBEAT 或 GOAL，默认 HEARTBEAT", required = false) String type,
            @Param(name = "runNow", description = "是否在创建后立即执行一次，默认 false", required = false) Boolean runNow,
            @Param(name = "maxTokens", description = "最大 token 预算（可选，主要用于 GOAL 任务）", required = false) Long maxTokens,
            @Param(name = "maxDurationMs", description = "最大执行时长（毫秒，可选，主要用于 GOAL 任务）", required = false) Long maxDurationMs,
            String __sessionId) {
        if (Assert.isEmpty(__sessionId)) {
            return "ERROR: 无活跃会话，无法新增定时任务。";
        }
        if (Assert.isEmpty(prompt)) {
            return "ERROR: prompt 不能为空。";
        }

        LoopTask.TaskType taskType = (type != null && "GOAL".equalsIgnoreCase(type))
                ? LoopTask.TaskType.GOAL
                : LoopTask.TaskType.HEARTBEAT;
        int interval = intervalMinutes != null ? intervalMinutes : 5;
        LoopTask task = new LoopTask(
                prompt, interval, cron,
                taskType,
                runNow != null && runNow
        );
        if (maxTokens != null) {
            task.setMaxTokens(maxTokens);
        }
        if (maxDurationMs != null) {
            task.setMaxDurationMs(maxDurationMs);
        }

        try {
            loopTasks.schedule(__sessionId, task);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "ERROR: 新增定时任务失败: " + e.getMessage();
        }

        return "OK: 定时任务已新增，taskId=" + task.getId();
    }

//    @ToolMapping(name = "remove_loop_task",
//            description = "删除当前会话中的定时任务（或循环任务）。taskId 来自 add_loop_task 的返回值。")
//    public String removeLoopTask(
//            @Param(name = "taskId", description = "待删除的定时任务 ID") String taskId,
//            String __sessionId) {
//        if (Assert.isEmpty(__sessionId)) {
//            return "ERROR: 无活跃会话，无法删除定时任务。";
//        }
//        if (Assert.isEmpty(taskId)) {
//            return "ERROR: taskId 不能为空。";
//        }
//
//        LoopTask task = loopTasks.getTaskById(__sessionId, taskId);
//        if (task == null) {
//            return "ERROR: 定时任务不存在，taskId=" + taskId;
//        }
//
//        loopTasks.remove(__sessionId, task);
//        return "OK: 定时任务已删除，taskId=" + taskId;
//    }

    interface LoopTaskOperations {
        LoopTask schedule(String sessionId, LoopTask task);

        LoopTask getTaskById(String sessionId, String taskId);

        void remove(String sessionId, LoopTask task);
    }

}
