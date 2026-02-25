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

import org.noear.solon.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 环境感知提供者 (Environment Awareness Provider)
 * 职责：扫描项目特征（技术栈、README、忽略规则），为 Agent 生成针对性的执行背景。
 *
 * @author noear
 * @since 3.9.4
 */
public class EnvSkillProvider {
    private static final Logger LOG = LoggerFactory.getLogger(EnvSkillProvider.class);

    private final Path rootPath;
    private final List<String> projectTags = new ArrayList<>();
    private final List<String> ignoreRules = new ArrayList<>();
    private String readmeSummary = "";

    public EnvSkillProvider(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
    }

    /**
     * 执行全量环境扫描
     */
    public EnvSkillProvider scan() {
        try {
            // 1. 探测技术栈标记 (Build Tool Awareness)
            detectStacks();

            // 2. 探测忽略规则 (Path Awareness)
            detectIgnoreRules();

            // 3. 提取 README 背景 (Context Awareness)
            detectReadme();

        } catch (Exception e) {
            LOG.warn("Env scan encountered minor issues: {}", e.getMessage());
        }
        return this;
    }

    private void detectStacks() {
        if (Files.exists(rootPath.resolve("pom.xml"))) projectTags.add("Maven/Java");
        if (Files.exists(rootPath.resolve("package.json"))) projectTags.add("Node.js");
        if (Files.exists(rootPath.resolve("go.mod"))) projectTags.add("Go");
        if (Files.exists(rootPath.resolve("requirements.txt"))) projectTags.add("Python");
        if (Files.exists(rootPath.resolve("docker-compose.yml"))) projectTags.add("Docker");
        if (Files.exists(rootPath.resolve("gradlew"))) projectTags.add("Gradle");
    }

    private void detectIgnoreRules() {
        Path gitignore = rootPath.resolve(".gitignore");
        if (Files.exists(gitignore)) {
            try {
                // 仅提取前 10 条规则作为 Agent 的“避坑”参考，防止读取过多
                List<String> lines = Files.readAllLines(gitignore, StandardCharsets.UTF_8);
                lines.stream()
                        .filter(l -> Utils.isNotEmpty(l) && !l.startsWith("#"))
                        .limit(10)
                        .forEach(ignoreRules::add);
            } catch (IOException ignored) {}
        }
    }

    private void detectReadme() throws IOException {
        Path readme = findReadme();
        if (readme != null) {
            String content = new String(Files.readAllBytes(readme), StandardCharsets.UTF_8);
            // 截取前 1500 字，保留最核心的安装与架构说明
            this.readmeSummary = content.substring(0, Math.min(content.length(), 1500));
        }
    }

    /**
     * 生成给 Agent 的引导指令
     */
    public String getEnvInstruction() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n### [PROJECT ENVIRONMENT CONTEXT]\n");
        sb.append("- **Current Directory**: `").append(rootPath).append("`\n");

        if (!projectTags.isEmpty()) {
            sb.append("- **Technical Stack**: ").append(String.join(", ", projectTags)).append("\n");
        }

        if (!ignoreRules.isEmpty()) {
            sb.append("- **Ignored Patterns**: ").append(String.join(", ", ignoreRules)).append(" (Avoid searching these directories)\n");
        }

        if (Utils.isNotEmpty(readmeSummary)) {
            sb.append("- **Project README Summary**: \n---\n")
                    .append(readmeSummary)
                    .append("\n---\n");
        }

        // 注入行为准则
        sb.append("\n### [OPERATING PRINCIPLES]\n")
                .append("1. **Locality**: 优先在项目根目录下寻找构建和配置文件。\n")
                .append("2. **Verification**: 修改关键逻辑后，应尝试查找并运行相关的单元测试或编译命令。\n")
                .append("3. **Constraint**: 严禁修改或搜索被 `.gitignore` 明确忽略的构建产物目录。\n");

        return sb.toString();
    }

    private Path findReadme() {
        String[] candidates = {"README.md", "readme.md", "README.txt", "README"};
        for (String name : candidates) {
            Path p = rootPath.resolve(name);
            if (Files.exists(p)) return p;
        }
        return null;
    }
}