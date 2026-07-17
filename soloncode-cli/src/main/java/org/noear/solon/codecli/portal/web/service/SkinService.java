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

import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.core.util.Assert;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 本地皮肤服务 —— 管理 ~/.soloncode/skins 下的 Zip 安装皮肤。
 *
 * <p>支持：列表、安装、卸载、导出、安全读文件、CSS 内相对资源改写。</p>
 *
 * @author noear 2026/7/17
 */
public class SkinService {
    private static final Pattern SKIN_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$");
    private static final Pattern CSS_URL_PATTERN = Pattern.compile("url\\((['\"]?)([^)'\"]+)\\1\\)", Pattern.CASE_INSENSITIVE);
    private static final long MAX_ZIP_BYTES = 8L * 1024 * 1024; // 8MB
    private static final long MAX_ASSET_BYTES = 2L * 1024 * 1024; // 2MB
    private static final Set<String> BUILTIN_NAMES = new LinkedHashSet<>(Arrays.asList(
            "default", "eyecare", "contrast"
    ));
    private static final Set<String> IMAGE_EXTS = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "webp", "gif"
    ));

    public static Set<String> builtinNames() {
        return Collections.unmodifiableSet(BUILTIN_NAMES);
    }

    public Path skinsRoot() {
        return Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessSkins()).toAbsolutePath().normalize();
    }

    public boolean isValidSkinName(String name) {
        return name != null && SKIN_NAME_PATTERN.matcher(name).matches();
    }

    public boolean isBuiltin(String name) {
        return name != null && BUILTIN_NAMES.contains(name);
    }

    /**
     * 列出已安装的本地皮肤（不含预置）。
     */
    public List<Map<String, Object>> listInstalled() {
        List<Map<String, Object>> list = new ArrayList<>();
        Path root = skinsRoot();
        if (!Files.isDirectory(root)) {
            return list;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) {
                    continue;
                }
                String name = dir.getFileName().toString();
                if (!isValidSkinName(name) || isBuiltin(name)) {
                    continue;
                }
                Path skinJson = dir.resolve("skin.json");
                Path skinCss = dir.resolve("skin.css");
                if (!Files.isRegularFile(skinCss)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("source", "local");
                item.put("displayName", name);
                item.put("description", "");
                item.put("author", "");
                item.put("version", "");
                item.put("hasPreview", Files.isRegularFile(dir.resolve("preview.png"))
                        || Files.isRegularFile(dir.resolve("preview.webp"))
                        || Files.isRegularFile(dir.resolve("preview.jpg")));
                if (Files.isRegularFile(skinJson)) {
                    try {
                        ONode meta = ONode.ofJson(new String(Files.readAllBytes(skinJson), StandardCharsets.UTF_8));
                        if (meta.isObject()) {
                            putIfNotBlank(item, "displayName", meta.get("displayName").getString());
                            if (Assert.isEmpty((String) item.get("displayName"))) {
                                putIfNotBlank(item, "displayName", meta.get("name").getString());
                            }
                            putIfNotBlank(item, "description", meta.get("description").getString());
                            putIfNotBlank(item, "author", meta.get("author").getString());
                            putIfNotBlank(item, "version", meta.get("version").getString());
                        }
                    } catch (Exception ignored) {
                        // meta 损坏时仍可列出皮肤
                    }
                }
                if (Assert.isEmpty((String) item.get("displayName"))) {
                    item.put("displayName", name);
                }
                list.add(item);
            }
        } catch (IOException e) {
            // ignore list errors
        }
        list.sort((a, b) -> String.valueOf(a.get("displayName")).compareToIgnoreCase(String.valueOf(b.get("displayName"))));
        return list;
    }

    /**
     * 从本地 zip 文件安装皮肤。返回安装后的 name。
     */
    public String installZipFile(Path zipFile) throws Exception {
        if (zipFile == null || !Files.isRegularFile(zipFile)) {
            throw new IllegalArgumentException("皮肤 zip 不存在");
        }
        String filename = zipFile.getFileName() != null ? zipFile.getFileName().toString() : "skin.zip";
        try (InputStream in = Files.newInputStream(zipFile)) {
            return installZip(in, filename);
        }
    }

    /**
     * 安装 zip 皮肤。返回安装后的 name。
     */
    public String installZip(InputStream zipStream, String originalFilename) throws Exception {
        if (zipStream == null) {
            throw new IllegalArgumentException("请上传皮肤 zip 文件");
        }

        Path root = skinsRoot();
        Files.createDirectories(root);

        Path tempZip = Files.createTempFile("soloncode-skin-", ".zip");
        Path tempDir = Files.createTempDirectory("soloncode-skin-unpack-");
        try {
            // 写入临时 zip，限制大小
            long written = 0;
            try (InputStream in = zipStream) {
                byte[] buf = new byte[8192];
                int n;
                try (java.io.OutputStream out = Files.newOutputStream(tempZip)) {
                    while ((n = in.read(buf)) != -1) {
                        written += n;
                        if (written > MAX_ZIP_BYTES) {
                            throw new IllegalArgumentException("皮肤包过大，上限 8MB");
                        }
                        out.write(buf, 0, n);
                    }
                }
            }

            unzipToDirectory(tempZip, tempDir);
            Path skinRoot = resolveSkinRoot(tempDir);
            if (skinRoot == null) {
                throw new IllegalArgumentException("无效皮肤包：未找到 skin.json 与 skin.css（支持扁平或单层目录）");
            }

            Path skinJson = skinRoot.resolve("skin.json");
            Path skinCss = skinRoot.resolve("skin.css");
            if (!Files.isRegularFile(skinJson) || !Files.isRegularFile(skinCss)) {
                throw new IllegalArgumentException("无效皮肤包：必须包含 skin.json 和 skin.css");
            }

            String name = null;
            try {
                ONode meta = ONode.ofJson(new String(Files.readAllBytes(skinJson), StandardCharsets.UTF_8));
                if (meta.isObject()) {
                    name = meta.get("name").getString();
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("skin.json 解析失败: " + e.getMessage());
            }

            if (!isValidSkinName(name)) {
                // 回退：用解压目录名或 zip 文件名
                String fallback = skinRoot.getFileName().toString();
                if (!isValidSkinName(fallback) && originalFilename != null) {
                    fallback = originalFilename.replaceAll("(?i)\\.zip$", "");
                }
                name = fallback;
            }
            if (!isValidSkinName(name)) {
                throw new IllegalArgumentException("皮肤 name 非法，仅允许字母数字、下划线、中划线，且以字母数字开头");
            }
            if (isBuiltin(name)) {
                throw new IllegalArgumentException("不能覆盖预置皮肤名: " + name);
            }

            // 校验包内资源
            validateSkinTree(skinRoot);

            Path target = root.resolve(name).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("非法皮肤路径");
            }
            if (Files.exists(target)) {
                deleteRecursively(target);
            }
            copyDirectory(skinRoot, target);
            return name;
        } finally {
            try {
                Files.deleteIfExists(tempZip);
            } catch (Exception ignored) {
            }
            try {
                deleteRecursively(tempDir);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 卸载本地皮肤。
     */
    public void uninstall(String name) throws Exception {
        if (!isValidSkinName(name)) {
            throw new IllegalArgumentException("非法皮肤名");
        }
        if (isBuiltin(name)) {
            throw new IllegalArgumentException("预置皮肤不可卸载");
        }
        Path target = skinsRoot().resolve(name).normalize();
        if (!target.startsWith(skinsRoot().normalize())) {
            throw new IllegalArgumentException("非法皮肤路径");
        }
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("皮肤不存在: " + name);
        }
        deleteRecursively(target);
    }
    
    /**
     * 导出本地皮肤为 zip 字节（扁平结构，便于再次安装分享）。
     */
    public byte[] exportZip(String name) throws Exception {
        if (!isValidSkinName(name)) {
            throw new IllegalArgumentException("非法皮肤名");
        }
        if (isBuiltin(name)) {
            throw new IllegalArgumentException("预置皮肤不可导出");
        }
        Path root = skinsRoot().normalize();
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || !Files.isDirectory(target)) {
            throw new IllegalArgumentException("皮肤不存在: " + name);
        }
        if (!Files.isRegularFile(target.resolve("skin.css"))) {
            throw new IllegalArgumentException("皮肤不存在: " + name);
        }
    
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            final Path srcNorm = target.normalize();
            Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path rel = srcNorm.relativize(file.normalize());
                    String entryName = rel.toString().replace('\\', '/');
                    if (entryName.isEmpty() || entryName.contains("..")) {
                        return FileVisitResult.CONTINUE;
                    }
                    // 跳过符号链接，避免导出到包外
                    if (Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    ZipEntry entry = new ZipEntry(entryName);
                    zos.putNextEntry(entry);
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return bos.toByteArray();
    }

    public boolean isInstalled(String name) {
        if (!isValidSkinName(name) || isBuiltin(name)) {
            return false;
        }
        Path css = skinsRoot().resolve(name).resolve("skin.css");
        return Files.isRegularFile(css);
    }

    /**
     * 安全解析皮肤内相对文件。
     */
    public Path resolveSkinFile(String name, String relativeFile) {
        if (!isValidSkinName(name) || isBuiltin(name)) {
            return null;
        }
        if (Assert.isEmpty(relativeFile)) {
            return null;
        }
        String file = relativeFile.replace('\\', '/');
        while (file.startsWith("./")) {
            file = file.substring(2);
        }
        if (file.startsWith("/") || file.contains("..")) {
            return null;
        }
        // 仅允许皮肤根下有限文件
        if (!isAllowedSkinFile(file)) {
            return null;
        }
        Path root = skinsRoot().resolve(name).normalize();
        Path resolved = root.resolve(file).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return null;
        }
        try {
            if (Files.size(resolved) > MAX_ASSET_BYTES && !file.equals("skin.css") && !file.equals("skin.json")) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return resolved;
    }

    /**
     * 读取并改写 skin.css 中的相对 url()，使其指向代理接口。
     */
    public String loadCssWithRewrittenUrls(String name) throws IOException {
        Path css = resolveSkinFile(name, "skin.css");
        if (css == null) {
            return null;
        }
        String content = new String(Files.readAllBytes(css), StandardCharsets.UTF_8);
        return rewriteCssUrls(content, name);
    }

    public String guessContentType(String file) {
        String lower = file == null ? "" : file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    // ==================== internal ====================

    private void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            map.put(key, value.trim());
        }
    }

    private boolean isAllowedSkinFile(String file) {
        if ("skin.css".equals(file) || "skin.json".equals(file)
                || "preview.png".equals(file) || "preview.webp".equals(file) || "preview.jpg".equals(file)
                || "preview.jpeg".equals(file)) {
            return true;
        }
        if (file.startsWith("assets/")) {
            String rest = file.substring("assets/".length());
            if (rest.isEmpty() || rest.contains("..") || rest.startsWith("/")) {
                return false;
            }
            int dot = rest.lastIndexOf('.');
            if (dot < 0) {
                return false;
            }
            String ext = rest.substring(dot + 1).toLowerCase(Locale.ROOT);
            return IMAGE_EXTS.contains(ext);
        }
        return false;
    }

    private void validateSkinTree(Path skinRoot) throws IOException {
        final Path rootNorm = skinRoot.normalize();
        Files.walkFileTree(skinRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = rootNorm.relativize(file.normalize());
                String relStr = rel.toString().replace('\\', '/');
                if (relStr.contains("..")) {
                    throw new IllegalArgumentException("皮肤包包含非法路径");
                }
                // 允许根文件与 assets 图片
                if (!isAllowedSkinFile(relStr) && !relStr.equals("README.md") && !relStr.equals("LICENSE")) {
                    // 宽松：忽略未知小文件，但禁止可执行/脚本
                    String lower = relStr.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".js") || lower.endsWith(".html") || lower.endsWith(".htm")
                            || lower.endsWith(".svg") || lower.endsWith(".exe") || lower.endsWith(".sh")) {
                        throw new IllegalArgumentException("皮肤包包含不允许的文件类型: " + relStr);
                    }
                }
                if (attrs.size() > MAX_ASSET_BYTES && !relStr.equals("skin.css")) {
                    throw new IllegalArgumentException("皮肤资源过大: " + relStr);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path resolveSkinRoot(Path unpackDir) throws IOException {
        if (Files.isRegularFile(unpackDir.resolve("skin.json"))
                && Files.isRegularFile(unpackDir.resolve("skin.css"))) {
            return unpackDir;
        }
        // 单层目录包装
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(unpackDir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.startsWith("__MACOSX") || name.startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    dirs.add(p);
                }
            }
        }
        if (dirs.size() == 1) {
            Path only = dirs.get(0);
            if (Files.isRegularFile(only.resolve("skin.json"))
                    && Files.isRegularFile(only.resolve("skin.css"))) {
                return only;
            }
        }
        // 深度 1 搜索
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(unpackDir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)
                        && Files.isRegularFile(p.resolve("skin.json"))
                        && Files.isRegularFile(p.resolve("skin.css"))) {
                    return p;
                }
            }
        }
        return null;
    }

    private void unzipToDirectory(Path zipFile, Path targetDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)));
        try {
            ZipEntry entry;
            long totalUncompressed = 0;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName == null || entryName.contains("..")) {
                    continue;
                }
                Path entryPath = targetDir.resolve(entryName).normalize();
                if (!entryPath.startsWith(targetDir.normalize())) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    totalUncompressed += Files.size(entryPath);
                    if (totalUncompressed > MAX_ZIP_BYTES * 4) {
                        throw new IllegalArgumentException("皮肤包解压后过大");
                    }
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        final Path srcNorm = source.normalize();
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = srcNorm.relativize(dir.normalize());
                Path dest = target.resolve(rel.toString());
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = srcNorm.relativize(file.normalize());
                Path dest = target.resolve(rel.toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            Files.delete(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String rewriteCssUrls(String css, String skinName) {
        Matcher m = CSS_URL_PATTERN.matcher(css);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String quote = m.group(1) == null ? "" : m.group(1);
            String raw = m.group(2).trim();
            if (raw.startsWith("data:") || raw.startsWith("http://") || raw.startsWith("https://")
                    || raw.startsWith("/web/settings/skins/file") || "none".equalsIgnoreCase(raw)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            String path = raw;
            if (path.startsWith("./")) {
                path = path.substring(2);
            }
            if (path.startsWith("/")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            if (path.contains("..")) {
                m.appendReplacement(sb, "url(" + quote + "about:blank" + quote + ")");
                continue;
            }
            String proxied = "/web/settings/skins/file?name=" + skinName + "&file=" + path;
            m.appendReplacement(sb, Matcher.quoteReplacement("url(" + quote + proxied + quote + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
