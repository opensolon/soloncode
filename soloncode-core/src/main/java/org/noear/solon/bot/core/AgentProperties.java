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
package org.noear.solon.bot.core;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.skills.restapi.ApiSource;

import java.io.Serializable;
import java.util.Map;

/**
 * Cli 配置
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
@Setter
public class AgentProperties implements Serializable {
    private String workDir = "./work/";

    private int maxSteps = 10;
    private boolean maxStepsAutoExtensible = false;

    private int sessionWindowSize = 10;

    private int summaryWindowSize = 12;
    private int summaryWindowToken = 15000;

    private boolean sandboxMode = true;
    private boolean thinkPrinted = false;

    private boolean hitlEnabled = false;
    private boolean subagentEnabled = true;
    private boolean agentTeamEnabled = true;
    private boolean browserEnabled = true;

    private boolean cliEnabled = true;
    private boolean cliPrintSimplified = true;

    private boolean webEnabled = false;
    private String webEndpoint = "/cli";

    private boolean acpEnabled = false;
    private String acpTransport = "stdio";
    private String acpEndpoint = "/acp";

    /**
     * 共享记忆配置
     */
    public boolean sharedMemoryEnabled = false;
    public SharedMemoryConfig sharedMemory = new SharedMemoryConfig();

    /**
     * 事件总线配置
     */
    public boolean eventBusEnabled = false;
    public EventBusConfig eventBus = new EventBusConfig();

    /**
     * 消息通道配置
     */
    public boolean messageChannelEnabled = false;
    public MessageChannelConfig messageChannel = new MessageChannelConfig();

    /**
     * Agent Teams 模式配置
     */
    public boolean teamsEnabled = false;
    public TeamsConfig teams = new TeamsConfig();

    public Map<String, McpServerParameters> mcpServers;
    public ChatConfig chatModel;

    /**
     * SubAgent 模型配置
     * 格式：subAgentCode -> modelName
     * 例如：{"explore": "glm-4-flash", "plan": "glm-4.7"}
     * 如果未配置，将使用默认的 chatModel.model
     */
    public Map<String, String> subAgentModels;

    private Map<String, ApiSource> restApis;
    @Deprecated
    private Map<String, String> mountPool;
    private Map<String, String> skillPools;

    /**
     * 共享记忆配置类
     */
    public static class SharedMemoryConfig {
        /**
         * 短期记忆TTL（毫秒，默认1小时）
         */
        public long shortTermTtl = 3600_000L;

        /**
         * 长期记忆TTL（毫秒，默认7天）
         */
        public long longTermTtl = 7 * 24 * 3600_000L;

        /**
         * 清理间隔（毫秒，默认5分钟）
         */
        public long cleanupInterval = 300_000L;

        /**
         * 写入时立即持久化
         */
        public boolean persistOnWrite = true;

        /**
         * 短期记忆最大数量
         */
        public int maxShortTermCount = 1000;

        /**
         * 长期记忆最大数量
         */
        public int maxLongTermCount = 500;
    }

    /**
     * 事件总线配置类
     */
    public static class EventBusConfig {
        /**
         * 异步处理线程数（默认CPU核心数）
         */
        public int asyncThreads = 4;

        /**
         * 事件历史最大数量
         */
        public int maxHistorySize = 1000;

        /**
         * 默认优先级（0-10）
         */
        public int defaultPriority = 5;

        /**
         * 处理超时时间（秒）
         */
        public int timeoutSeconds = 30;
    }

    /**
     * 消息通道配置类
     */
    public static class MessageChannelConfig {
        /**
         * 处理线程数
         */
        public Integer threads;

        /**
         * 默认消息TTL（毫秒，默认60秒）
         */
        public long defaultTtl = 60_000L;

        /**
         * 每个代理的最大队列长度
         */
        public int maxQueueSize = 1000;

        /**
         * 是否持久化消息
         */
        public boolean persistMessages = true;
    }

    /**
     * Agent Teams 模式配置类
     */
    public static class TeamsConfig {
        /**
         * 任务操作线程池大小
         * 默认：CPU核心数 * 2，最小10
         */
        public Integer taskExecutorThreads;

        /**
         * 事件发布线程池大小
         * 默认：1（单线程）
         */
        public Integer eventExecutorThreads;

        /**
         * 最大保留已完成任务数
         * 默认：100
         */
        public int maxCompletedTasks = 100;

        /**
         * 最大依赖深度限制
         * 默认：100
         */
        public int maxDependencyDepth = 100;
    }

    /**
     * 子代理并发控制配置类
     * 用于控制同时发起的子代理请求数量，避免触发API速率限制
     */
    public static class SubagentConcurrencyConfig {
        /**
         * 最大并发子代理数
         * 默认：1（串行执行，避免触发速率限制）
         * 设置为 2-3 可以适当提高性能，但可能触发速率限制
         */
        public int maxConcurrent = 2;

        /**
         * 子代理调用间隔（毫秒）
         * 默认：1000ms（1秒）
         * 在串行模式下，每次调用后等待的时间
         */
        public long callIntervalMs = 1000L;

        /**
         * 获取执行许可的超时时间（毫秒）
         * 默认：60000ms（60秒）
         * 当达到并发限制时，等待获取许可的最长时间
         */
        public long acquireTimeoutMs = 60000L;

    }

    /**
     * 子代理并发控制配置
     */
    public SubagentConcurrencyConfig subagentConcurrency = new SubagentConcurrencyConfig();
}
