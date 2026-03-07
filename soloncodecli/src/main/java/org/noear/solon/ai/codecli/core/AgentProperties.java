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

import lombok.Getter;
import lombok.Setter;
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
@Getter
@Setter
@Configuration
@BindProps(prefix="solon.code.cli")
public class AgentProperties {
    private String workDir = "./work";

    private int maxSteps = 30;
    private boolean maxStepsAutoExtensible = false;

    private int sessionWindowSize = 10;
    private int summaryWindowSize = 15;

    private boolean sandboxMode = true;
    private boolean thinkPrinted = false;

    private boolean hitlEnabled = false;
    private boolean subagentEnabled = true;

    private boolean cliEnabled = true;
    private boolean cliPrintSimplified = true;

    private boolean webEnabled = false;
    private String webEndpoint = "/cli";

    private boolean acpEnabled = false;
    private String acpTransport = "stdio";
    private String acpEndpoint = "/acp";


    private Map<String, McpServerParameters> mcpServers;
    private ChatConfig chatModel;
    @Deprecated
    private Map<String, String> mountPool;
    private Map<String, String> skillPools;
}
