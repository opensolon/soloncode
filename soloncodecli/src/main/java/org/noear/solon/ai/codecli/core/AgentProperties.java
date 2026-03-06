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
package org.noear.solon.ai.codecli.core;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.annotation.BindProps;
import org.noear.solon.annotation.Configuration;

import java.util.Map;

/**
 * Cli 配置
 *
 * @author noear
 * @since 3.9.1
 */
@Configuration
@BindProps(prefix="solon.code.cli")
public class AgentProperties {
    public String workDir = "./work";

    public int maxSteps = 30;
    public boolean maxStepsAutoExtensible = false;

    public int sessionWindowSize = 10;
    public int summaryWindowSize = 15;

    public boolean sandboxMode = true;
    public boolean thinkPrinted = false;
    public boolean hitlEnabled = false;

    public boolean cliEnabled = true;
    public boolean cliPrintSimplified = true;

    public boolean webEnabled = false;
    public String webEndpoint = "/cli";

    public boolean acpEnabled = false;
    public String acpTransport = "stdio";
    public String acpEndpoint = "/acp";

    public boolean subAgentEnabled = false;

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

    public Map<String, McpServerParameters> mcpServers;
    public ChatConfig chatModel;

    /**
     * SubAgent 模型配置
     * 格式：subAgentCode -> modelName
     * 例如：{"explore": "glm-4-flash", "plan": "glm-4.7"}
     * 如果未配置，将使用默认的 chatModel.model
     */
    public Map<String, String> subAgentModels;

    @Deprecated
    public Map<String, String> mountPool;
    public Map<String, String> skillPools;

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
        public Integer asyncThreads;

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
}
