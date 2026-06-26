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
package org.noear.solon.codecli.portal.web.service;

import org.noear.solon.codecli.portal.web.WebController;
import org.noear.solon.core.handle.Result;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 文件服务 —— 封装工作区文件浏览、搜索、读取等操作的核心业务逻辑。
 *
 * <p>提供工作区目录结构的层级化浏览、关键词文件搜索、文件内容读取等能力。</p>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>通过 workspace 路径构造，所有文件操作均基于此路径</li>
 *   <li>内部维护排除目录列表，自动过滤构建产物、IDE 配置等无需展示的目录</li>
 *   <li>供 WebController 直接调用，Controller 层仅做参数解析和结果转发</li>
 * </ul>
 *
 * @author noear 2026-5-30
 * @see WebController
 */
public class FileService {
    /** 工作区根目录路径 */
    private final String workspace;

    /**
     * 文件树浏览时排除的目录名称集合。
     * <p>包含各类构建产物、IDE 配置、版本控制等无需展示的目录，
     * 如 .git、.idea、node_modules、target、__pycache__ 等。</p>
     */
    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".idea", ".soloncode", "node_modules", "target", "__pycache__", ".gradle", ".mvn", "build"
    ));

    /**
     * 构造函数。
     *
     * @param workspace 工作区根目录路径
     */
    public FileService(String workspace) {
        this.workspace = workspace;
    }

    // ==================== 公开业务方法 ====================

    /**
     * 工作区文件树浏览。
     * <p>以工作区根目录为基准，按指定路径和深度返回目录结构。
     * 排除以点号开头的隐藏文件和 {@link #EXCLUDED_DIRS} 中的目录。</p>
     *
     * @param path  相对路径，基于工作区根目录；为空时从根目录开始
     * @param depth 展开深度，默认为 1（仅展开第一层）
     * @return 文件树列表，每项包含 name、path、type、expanded、children
     */
    public Result<List<Map>> tree(String path, Integer depth) {
        if (depth == null || depth < 1) depth = 1;
        if (path == null) path = "";
        if (path.contains("..")) {
            return Result.failure(400, "Invalid path");
        }

        java.nio.file.Path workspacePath = Paths.get(workspace).toAbsolutePath().normalize();
        java.nio.file.Path target = workspacePath.resolve(path).toAbsolutePath().normalize();

        if (!target.startsWith(workspacePath)) {
            return Result.failure(403, "Access denied");
        }
        if (!target.toFile().exists() || !target.toFile().isDirectory()) {
            return Result.failure(404, "Directory not found");
        }

        List<Map> tree = buildTree(target, workspacePath, depth, 1);
        return Result.succeed(tree);
    }

    /**
     * 工作区文件搜索。
     * <p>递归扫描整个工作区，返回路径中包含关键词的文件列表。
     * 排除规则与文件树接口一致：隐藏文件和 EXCLUDED_DIRS 中的目录。</p>
     *
     * @param keyword 搜索关键词，匹配文件路径（大小写不敏感）
     * @return 匹配的文件列表，每项包含 name、path、type
     */
    public Result<List<Map>> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Result.failure(400, "Keyword is required");
        }
        if (keyword.contains("..")) {
            return Result.failure(400, "Invalid keyword");
        }

        java.nio.file.Path workspacePath = Paths.get(workspace).toAbsolutePath().normalize();
        String kw = keyword.trim().toLowerCase();

        List<Map> results = new ArrayList<>();
        searchFiles(workspacePath.toFile(), workspacePath, kw, results, 0);

        if (results.size() > 200) {
            results = results.subList(0, 200);
        }

        return Result.succeed(results);
    }

    /**
     * 读取工作区文件内容。
     * <p>以工作区根目录为基准，读取指定路径的文件文本内容。
     * 支持安全路径校验和文件大小限制（最大 2MB）。</p>
     *
     * @param path 相对路径，基于工作区根目录
     * @return 文件信息，包含 content、path、name、size
     */
    public Result<Map> read(String path) {
        if (path == null || path.trim().isEmpty()) {
            return Result.failure(400, "Path is required");
        }
        if (path.contains("..")) {
            return Result.failure(400, "Invalid path");
        }

        java.nio.file.Path workspacePath = Paths.get(workspace).toAbsolutePath().normalize();
        java.nio.file.Path target = workspacePath.resolve(path).toAbsolutePath().normalize();

        if (!target.startsWith(workspacePath)) {
            return Result.failure(403, "Access denied");
        }
        // 检测符号链接，防止越权读取工作区外部的文件
        if (Files.isSymbolicLink(target)) {
            return Result.failure(403, "Access denied: symlink");
        }
        if (!target.toFile().exists() || target.toFile().isDirectory()) {
            return Result.failure(404, "File not found");
        }

        File file = target.toFile();
        // 限制文件大小：2MB
        if (file.length() > 2 * 1024 * 1024) {
            return Result.failure(413, "File too large (max 2MB)");
        }

        // 读取文件内容（尝试 UTF-8，失败回退系统默认编码）
        String content;
        try {
            content = new String(java.nio.file.Files.readAllBytes(target), "UTF-8");
        } catch (Exception e) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                content = sb.toString();
            } catch (Exception ex) {
                return Result.failure(500, "Failed to read file: " + ex.getMessage());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content);
        data.put("path", path);
        data.put("name", file.getName());
        data.put("size", file.length());

        return Result.succeed(data);
    }

    // ==================== 内部方法 ====================

    /**
     * 递归构建文件树结构。
     * <p>对指定目录进行扫描，目录排在前面、文件排在后面，均按名称字典序排列。
     * 跳过以点号开头的隐藏文件和 {@link #EXCLUDED_DIRS} 中定义的目录。
     * 当达到最大深度时，目录节点不再展开（children 为 null）。</p>
     *
     * @param dir          当前扫描的目录路径
     * @param workspacePath 工作区根路径，用于计算相对路径
     * @param maxDepth     最大展开深度
     * @param currentDepth 当前递归深度
     * @return 当前层级的文件/目录信息列表
     */
    private List<Map> buildTree(java.nio.file.Path dir, java.nio.file.Path workspacePath, int maxDepth, int currentDepth) {
        File[] files = dir.toFile().listFiles();
        if (files == null) return Collections.emptyList();

        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        List<Map> result = new ArrayList<>();
        for (File f : files) {
            if (f.getName().startsWith(".") || EXCLUDED_DIRS.contains(f.getName())) continue;
            // 跳过符号链接，防止遍历到工作区外部的文件
            if (Files.isSymbolicLink(f.toPath())) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", f.getName());
            item.put("path", workspacePath.relativize(f.toPath().toAbsolutePath().normalize()).toString().replace('\\', '/'));
            item.put("type", f.isDirectory() ? "directory" : "file");

            if (f.isDirectory() && currentDepth < maxDepth) {
                item.put("expanded", true);
                item.put("children", buildTree(f.toPath(), workspacePath, maxDepth, currentDepth + 1));
            } else if (f.isDirectory()) {
                item.put("expanded", false);
                item.put("children", null);
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 递归搜索匹配关键词的文件。
     *
     * @param dir       当前扫描的目录
     * @param workspacePath 工作区根路径，用于计算相对路径
     * @param keyword   小写化后的搜索关键词
     * @param results   收集结果的列表
     * @param depth     当前递归深度，超过 20 层停止
     */
    private void searchFiles(File dir, java.nio.file.Path workspacePath, String keyword, List<Map> results, int depth) {
        if (depth > 20) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.getName().startsWith(".") || (f.isDirectory() && EXCLUDED_DIRS.contains(f.getName()))) continue;
            // 跳过符号链接，防止遍历到工作区外部的文件
            if (Files.isSymbolicLink(f.toPath())) continue;

            String relativePath = workspacePath.relativize(f.toPath().toAbsolutePath().normalize()).toString().replace('\\', '/');

            if (relativePath.toLowerCase().contains(keyword)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", f.getName());
                item.put("path", relativePath);
                item.put("type", f.isDirectory() ? "directory" : "file");
                results.add(item);
            }

            if (f.isDirectory()) {
                searchFiles(f, workspacePath, keyword, results, depth + 1);
            }
        }
    }
}
