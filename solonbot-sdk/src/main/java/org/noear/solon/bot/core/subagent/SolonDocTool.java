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
package org.noear.solon.bot.core.subagent;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.bot.core.tool.WebfetchTool;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读取 Solon 文档工具
 *
 * @author bai
 * @since 3.9.5
 */
public class SolonDocTool {
    private static final Logger LOG = LoggerFactory.getLogger(SolonDocTool.class);

    // 文档缓存（全局共享）
    private static final Map<String, String> DOC_CACHE = new ConcurrentHashMap<>();

    // Solon 官网文档基础 URL
    private static final String SOLON_DOCS_BASE_URL = "https://solon.noear.org/article/";

    // 工作目录
    private final String workDir;

    // 本地缓存目录
    private String cacheDir;

    /**
     * 默认构造函数（使用当前工作目录）
     */
    public SolonDocTool(String workDir) {
        this.workDir = workDir;
        this.cacheDir = this.workDir + File.separator + ".soloncode" + File.separator + "cache" + File.separator + "docs";

    }

    /**
     * 读取 Solon 官网文档
     */
    @ToolMapping(
            name = "solon_doc_read",
            description = "读取 Solon 官网文档。支持文档名称如：learn-start, learn-features, agent-quick-start, agent-chat-model, agent-tools, agent-skill 等"
    )
    public String fetch(
            @Param(value = "docName", description = "文档名称（如 'learn-start'）") String docName) {

        // 清理文档名称
        String cleanDocName = docName.trim().replace(".md", "");

        // 检查缓存
        String cached = DOC_CACHE.get(cleanDocName);
        if (cached != null) {
            LOG.debug("从内存缓存读取 Solon 文档: {}", cleanDocName);
            return formatDocContent(cleanDocName, cached);
        }

        // 尝试从本地缓存文件读取
        String localCache = readLocalCache(cleanDocName);
        if (localCache != null) {
            DOC_CACHE.put(cleanDocName, localCache);
            LOG.info("从本地缓存读取 Solon 文档: {}", cleanDocName);
            return formatDocContent(cleanDocName, localCache);
        }

        // 从网络获取文档
        String docUrl = SOLON_DOCS_BASE_URL + cleanDocName + "?format=md";

        try {
            LOG.info("从 Solon 官网读取文档: {} -> {}", cleanDocName, docUrl);

            // 使用 WebfetchTool 获取文档
            WebfetchTool webfetch = WebfetchTool.getInstance();
            Document document = webfetch.webfetch(docUrl, "markdown", 120);

            String content = document.getContent();
            if (content != null && !content.isEmpty()) {
                // 缓存到内存
                DOC_CACHE.put(cleanDocName, content);

                // 保存到本地缓存文件
                saveLocalCache(cleanDocName, content);

                LOG.info("Solon 文档获取成功: {}", cleanDocName);
                return formatDocContent(cleanDocName, content);
            } else {
                return "无法获取文档内容: " + docName + "\n" +
                       "请检查文档名称是否正确，或访问 https://solon.noear.org/article/" + cleanDocName + " 确认";
            }

        } catch (Throwable e) {
            LOG.warn("读取 Solon 文档失败: {}, error={}", cleanDocName, e.getMessage());
            return "读取文档失败: " + e.getMessage();
        }
    }

    /**
     * 列出所有可用的 Solon 文档
     */
    @ToolMapping(
            name = "list_solon_docs",
            description = "列出所有可用的 Solon 官方文档"
    )
    public String list() {
        StringBuilder sb = new StringBuilder("# 可用的 Solon 文档\n\n");

        // 分类列出文档
        sb.append("## 快速入门\n");
        String[][] quickStartDocs = {
                {"learn-start", "Solon 快速入门"},
                {"learn-features", "Solon 特性介绍"},
                {"learn-demo", "Solon 示例代码"}
        };
        for (String[] doc : quickStartDocs) {
            sb.append(String.format("- **%s**: %s (https://solon.noear.org/article/%s)\n", doc[0], doc[1], doc[0]));
        }

        sb.append("\n## Agent SDK\n");
        String[][] agentDocs = {
                {"agent-quick-start", "Agent SDK 快速开始"},
                {"agent-chat-model", "Chat Model 使用"},
                {"agent-tools", "Agent 工具开发"},
                {"agent-skill", "Agent 技能系统"},
                {"agent-react", "ReAct 模式详解"}
        };
        for (String[] doc : agentDocs) {
            sb.append(String.format("- **%s**: %s (https://solon.noear.org/article/%s)\n", doc[0], doc[1], doc[0]));
        }

        sb.append("\n## 高级主题\n");
        String[][] advancedDocs = {
                {"learn-architecture", "Solon 架构设计"},
                {"learn-performance", "性能优化指南"},
                {"learn-plugin", "插件开发"},
                {"learn-testing", "测试指南"}
        };
        for (String[] doc : advancedDocs) {
            sb.append(String.format("- **%s**: %s (https://solon.noear.org/article/%s)\n", doc[0], doc[1], doc[0]));
        }

        sb.append("\n提示：使用 read_solon_doc 工具读取具体文档\n");
        sb.append("例如：read_solon_doc(\"learn-start\")\n");
        sb.append("\n缓存目录: ").append(cacheDir).append("\n");

        return sb.toString();
    }

    /**
     * 清除文档缓存
     */
    @ToolMapping(
            name = "clear_solon_doc_cache",
            description = "清除 Solon 文档缓存（包括内存和本地文件缓存）"
    )
    public String clearCache() {
        int memoryCacheSize = DOC_CACHE.size();
        DOC_CACHE.clear();

        int fileCacheSize = clearLocalCache();

        LOG.info("Solon 文档缓存已清除: 内存{}个, 本地{}个文件", memoryCacheSize, fileCacheSize);
        return String.format("Solon 文档缓存已清除\n- 内存缓存: %d 条\n- 本地文件: %d 个\n- 缓存目录: %s",
                memoryCacheSize, fileCacheSize, cacheDir);
    }

    /**
     * 从本地缓存读取
     */
    private String readLocalCache(String docName) {
        try {
            File cacheFile = new File(cacheDir, docName + ".md");

            if (cacheFile.exists() && cacheFile.isFile()) {
                byte[] bytes = Files.readAllBytes(Paths.get(cacheFile.getAbsolutePath()));
                String content = new String(bytes, StandardCharsets.UTF_8);
                LOG.debug("从本地缓存读取 Solon 文档: {}", cacheFile.getAbsolutePath());
                return content;
            }
        } catch (Throwable e) {
            LOG.debug("读取本地缓存失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 保存到本地缓存
     */
    private void saveLocalCache(String docName, String content) {
        try {
            File dir = new File(cacheDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File cacheFile = new File(cacheDir, docName + ".md");
            Files.write(Paths.get(cacheFile.getAbsolutePath()), content.getBytes(StandardCharsets.UTF_8));
            LOG.debug("Solon 文档已缓存到本地: {}", cacheFile.getAbsolutePath());
        } catch (Throwable e) {
            LOG.debug("保存本地缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 清除本地文件缓存
     */
    private int clearLocalCache() {
        try {
            File dir = new File(cacheDir);
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
                int count = 0;
                if (files != null) {
                    for (File file : files) {
                        if (file.delete()) {
                            count++;
                        }
                    }
                }
                return count;
            }
        } catch (Throwable e) {
            LOG.debug("清除本地缓存失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 格式化文档内容
     */
    private String formatDocContent(String docName, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Solon 文档: ").append(docName).append("\n");
        sb.append("来源: https://solon.noear.org/article/").append(docName).append("\n");
        sb.append("---\n\n");
        sb.append(content);
        return sb.toString();
    }
}
