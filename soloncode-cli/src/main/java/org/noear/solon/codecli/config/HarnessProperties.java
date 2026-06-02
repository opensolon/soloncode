/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.config;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.openapi.ApiSource;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 马具配置属性
 *
 * @author noear
 * @since 3.10.0
 */
@Getter
@Setter
@Preview("3.10")
@Deprecated
public class HarnessProperties implements Serializable {

    //马具目录
    private final String harnessHome;

    //默认工作区
    private String workspace = "work";

    //系统提示词
    private String systemPrompt;

    //主代理工具权限
    private List<String> tools = new CopyOnWriteArrayList<>();

    // 禁用工具（全局）
    private List<String> disallowedTools = new CopyOnWriteArrayList<>();

    //最大步数
    private int maxSteps = 30;

    //自我反思
    private boolean autoRethink = true;

    private int sessionWindowSize = 8;
    private int summaryWindowSize = 30;
    private int summaryWindowToken = 30000;
    private String summaryModel; //摘要大模型

    private boolean memoryIsolation = false;
    private boolean memoryEnabled = true;

    private boolean sandboxMode = true;
    private boolean hitlEnabled = false;
    private boolean subagentEnabled = true;
    private boolean bashAsyncEnabled = false;

    private String userAgent;

    //api 重试次数
    private int apiRetries = 3;
    //Mcp 重试次数
    private int mcpRetries = 3;
    //模型重试次数
    private int modelRetries = 3;

    //扩展
    private List<HarnessExtension> extensions = new CopyOnWriteArrayList<>();

    //大模型
    private List<ChatConfig> models = new CopyOnWriteArrayList<>();
    /**
     * @deprecated 4.0.0
     */
    @Deprecated
    private Map<String, String> skillPools = new ConcurrentHashMap<>();
    /**
     * @deprecated 4.0.0
     */
    @Deprecated
    private List<String> agentPools = new CopyOnWriteArrayList<>();
    //mcp集
    private Map<String, McpServerParameters> mcpServers = new ConcurrentHashMap<>();
    //api集
    private Map<String, ApiSource> apiServers = new ConcurrentHashMap<>();
    //lsp集
    private Map<String, LspServerParameters> lspServers = new ConcurrentHashMap<>();

    public HarnessProperties(String harnessHome) {
        if (Assert.isEmpty(harnessHome)) {
            harnessHome = ".solon/";
        } else if (harnessHome.endsWith("/") == false) {
            harnessHome = harnessHome + "/";
        }

        this.harnessHome = harnessHome;
    }

    /**
     * @deprecated 4.0.0
     *
     */
    @Deprecated
    public Map<String, String> getSkillPools() {
        return skillPools;
    }


    public List<ChatConfig> getModels() {
        return models;
    }


    public boolean isAutoRethink() {
        return autoRethink;
    }


    //--------------------------

    /**
     * 马具主目录
     */
    public final String getHarnessHome() {
        return harnessHome;
    }

    /**
     * 马具会话存放区
     */
    public final String getHarnessSessions() {
        return getHarnessHome() + "sessions/";
    }

    /**
     * 马具技能存放区
     */
    public final String getHarnessSkills() {
        return getHarnessHome() + "skills/";
    }

    /**
     * 马具子代理描述存放区
     */
    public final String getHarnessAgents() {
        return getHarnessHome() + "agents/";
    }

    /**
     * 马具命令描述存放区
     */
    public final String getHarnessCommands() {
        return getHarnessHome() + "commands/";
    }

    /**
     * 马具记忆存放区
     */
    public final String getHarnessMemory() {
        return getHarnessHome() + "memory/";
    }

    /**
     * 马具下载存放区
     */
    public final String getHarnessDownload() {
        return getHarnessHome() + "download/";
    }

    /**
     * 马具连接通道存放区
     */
    public final String getHarnessChannels() {
        return getHarnessHome() + "channels/";
    }
}