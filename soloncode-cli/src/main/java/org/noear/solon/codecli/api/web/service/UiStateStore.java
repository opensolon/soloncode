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
package org.noear.solon.codecli.api.web.service;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Web UI 轻量状态存储（后端记忆）——把「刷新后要恢复的界面状态」从前端 localStorage 搬到服务端。
 *
 * <h3>为什么需要它</h3>
 * <p>localStorage / sessionStorage 均按 <b>origin（scheme + host + port）</b> 隔离。
 * SolonCode Web 支持随机端口启动（{@code --port 0}），端口一变 origin 就变，
 * 浏览器会给出一个空白的 localStorage：表现为「重启后不会自动回到上次的对话」、
 * 主题/字号/皮肤/侧栏布局全部回到默认。固定端口只是碰巧躲过了这件事。</p>
 *
 * <h3>存什么</h3>
 * <p>只存<b>界面偏好与指针</b>（会话/工作区本身仍以磁盘上的真实数据为准）：
 * 当前活动会话 ID、主题、语言、皮肤、字体、侧栏/文件树布局、活动标签页等。
 * 值统一为字符串（对齐 localStorage 语义），键名沿用前端历史键名，
 * 升级后无需做一次性迁移：老用户的 localStorage 里如果有值，前端会在首次保存时补写上来。</p>
 *
 * <h3>落在哪</h3>
 * <pre>
 * &lt;userHome&gt;/&lt;harnessHome&gt;/ui-state.json     // 即 ~/.soloncode/ui-state.json
 * </pre>
 * <p>位置只认用户级目录一处（{@code HarnessEngine.getUserHome()} + {@code getHarnessHome()}），
 * 与进程启动目录、监听端口都无关——这正是本存储存在的意义。切勿改用 {@code getUserDir()}：
 * 那会让同一进程随启动位置读写不同文件。</p>
 *
 * <h3>文件格式</h3>
 * <pre>
 * {
 *   "version": 1,
 *   "scopes": {
 *     "_":  { "chat-theme": "dark", "soloncode-active-session": "web-xxx" },
 *     "u1": { "chat-theme": "light" }
 *   }
 * }
 * </pre>
 * <p>{@code scopes} 的作用域键来自登录用户（启用「对话隔离」时按用户分片，否则统一为
 * {@link #SCOPE_DEFAULT}）——与「会话按 ownerUserId 过滤」的口径保持一致，
 * 避免多用户共用一个服务时互相改写对方的界面状态。</p>
 *
 * <p>写入采用「同目录临时文件 + 原子移动」，读取永远从磁盘现读（跨进程共享同一文件时，
 * 各自读-改-写，不会用内存里的旧快照覆盖对方的改动）。</p>
 *
 * @author noear 2026/10
 * @since 3.9.x
 */
public class UiStateStore {
    private static final Logger LOG = LoggerFactory.getLogger(UiStateStore.class);

    /**
     * 状态文件名
     */
    public static final String STORE_FILE = "ui-state.json";

    /**
     * 文件格式版本
     */
    private static final int VERSION = 1;

    /**
     * 默认作用域键（未启用对话隔离时全部用户共享）
     */
    public static final String SCOPE_DEFAULT = "_";

    /**
     * 作用域数量上限（防止匿名/长期运行无限增长）
     */
    private static final int MAX_SCOPES = 50;

    /**
     * 单个作用域的键数量上限
     */
    private static final int MAX_KEYS = 100;

    /**
     * 单值长度上限
     */
    private static final int MAX_VALUE_LENGTH = 2048;

    /**
     * 单个作用域全部值长度上限
     */
    private static final int MAX_TOTAL_VALUE_LENGTH = 64 * 1024;

    /**
     * 合法键名：字母数字与 {@code . _ @ : -}（会话/工作区标识如 {@code ws-<md5>-name}、{@code @mount} 都在此范围内）
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._@:\\-]{1,128}");

    /**
     * 进程内写锁（跨进程靠「读-改-写 + 原子移动」保证不丢键）
     */
    private static final Object LOCK = new Object();

    private final Path storePath;

    public UiStateStore(Path storePath) {
        if (storePath == null) {
            throw new IllegalArgumentException("storePath must not be null");
        }
        this.storePath = storePath;
    }

    public Path getStorePath() {
        return storePath;
    }

    /**
     * 状态文件路径：{@code <harnessRoot>/<harnessHome>/ui-state.json}。
     *
     * @param harnessRoot 用户主目录（{@code HarnessEngine.getUserHome()}）
     * @param harnessHome 用户级 harness 目录（{@code HarnessEngine.getHarnessHome()}，形如 {@code .soloncode/}）
     */
    public static Path storePath(String harnessRoot, String harnessHome) {
        return Paths.get(harnessRoot, harnessHome, STORE_FILE).toAbsolutePath();
    }

    /**
     * 作用域键归一：空值统一落到 {@link #SCOPE_DEFAULT}。
     */
    public static String normalizeScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return SCOPE_DEFAULT;
        }
        return scope.trim();
    }

    /**
     * 键名是否合法（越界键直接丢弃，避免污染文件与前端）
     */
    public static boolean isValidKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }

    /**
     * 读取指定作用域的全部键值；无记录或文件损坏时返回空表。
     */
    public Map<String, String> load(String scope) {
        synchronized (LOCK) {
            Map<String, String> values = readAll().get(normalizeScope(scope));
            return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
        }
    }

    /**
     * 合并保存：值为 {@code null} 表示删除该键，返回合并后的该作用域全量键值。
     * <p>非法键、超长值、非字符串值一律忽略（best-effort，不影响其它键的保存）。</p>
     */
    public Map<String, String> merge(String scope, Map<String, String> changes) {
        String scopeKey = normalizeScope(scope);
        synchronized (LOCK) {
            Map<String, Map<String, String>> all = readAll();
            Map<String, String> current = all.get(scopeKey);
            if (current == null) {
                current = new LinkedHashMap<>();
                all.put(scopeKey, current);
            }

            if (changes != null) {
                for (Map.Entry<String, String> entry : changes.entrySet()) {
                    String key = entry.getKey();
                    if (isValidKey(key) == false) {
                        continue;
                    }

                    String value = entry.getValue();
                    if (value == null) {
                        current.remove(key);
                    } else if (value.length() <= MAX_VALUE_LENGTH && current.size() < MAX_KEYS) {
                        current.put(key, value);
                    }
                }
            }

            if (current.isEmpty()) {
                all.remove(scopeKey);
            }

            try {
                writeAll(all);
            } catch (IOException e) {
                //保存失败不能让前端拿到假成功：抛出让控制器报告失败（键值仍在内存态里，下次保存会重试）
                throw new IllegalStateException("Failed to save ui state: " + e.getMessage(), e);
            }

            return new LinkedHashMap<>(current);
        }
    }

    /**
     * 从磁盘读取全部作用域；文件不存在/不可解析时返回空表。
     */
    private Map<String, Map<String, String>> readAll() {
        if (Files.exists(storePath) == false) {
            return new LinkedHashMap<>();
        }

        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try {
            ONode root = ONode.ofJson(new String(Files.readAllBytes(storePath), StandardCharsets.UTF_8));
            if (root == null || root.isObject() == false) {
                return result;
            }

            ONode scopes = root.get("scopes");
            if (scopes == null || scopes.isObject() == false) {
                return result;
            }

            for (Map.Entry<String, ONode> scopeEntry : scopes.getObject().entrySet()) {
                ONode scopeNode = scopeEntry.getValue();
                if (scopeNode == null || scopeNode.isObject() == false) {
                    continue;
                }

                Map<String, String> values = new LinkedHashMap<>();
                int totalLength = 0;
                for (Map.Entry<String, ONode> entry : scopeNode.getObject().entrySet()) {
                    String key = entry.getKey();
                    ONode node = entry.getValue();
                    if (isValidKey(key) == false || node == null || node.isNull()) {
                        continue;
                    }

                    String value = node.getString();
                    if (value == null || value.length() > MAX_VALUE_LENGTH
                            || values.size() >= MAX_KEYS
                            || totalLength + value.length() > MAX_TOTAL_VALUE_LENGTH) {
                        continue;
                    }

                    totalLength += value.length();
                    values.put(key, value);
                }

                if (values.isEmpty() == false) {
                    result.put(scopeEntry.getKey(), values);
                }
            }
        } catch (Exception e) {
            //损坏状态文件不该阻断任何请求，但必须留痕：否则界面状态会无声消失
            LOG.warn("[UiState] Failed to load {}: {}", storePath, e.toString());
            return new LinkedHashMap<>();
        }

        return result;
    }

    /**
     * 全量写盘（同目录临时文件 + 原子移动）。
     */
    private void writeAll(Map<String, Map<String, String>> all) throws IOException {
        Path parent = storePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        //作用域上限：超出的按插入序丢弃最旧的部分（LinkedHashMap 保序），避免文件无限膨胀
        Map<String, Map<String, String>> trimmed = all;
        if (all.size() > MAX_SCOPES) {
            trimmed = new LinkedHashMap<>();
            int skip = all.size() - MAX_SCOPES;
            for (Map.Entry<String, Map<String, String>> entry : all.entrySet()) {
                if (skip-- > 0) {
                    continue;
                }
                trimmed.put(entry.getKey(), entry.getValue());
            }
        }

        ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
        root.set("version", VERSION);
        ONode scopes = root.getOrNew("scopes");
        for (Map.Entry<String, Map<String, String>> scopeEntry : trimmed.entrySet()) {
            ONode scopeNode = scopes.getOrNew(scopeEntry.getKey());
            for (Map.Entry<String, String> entry : scopeEntry.getValue().entrySet()) {
                scopeNode.set(entry.getKey(), entry.getValue());
            }
        }

        Path tmp = storePath.resolveSibling(storePath.getFileName() + ".tmp");
        Files.write(tmp, root.toJson().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            try {
                Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(atomicFailure);
                throw fallbackFailure;
            }
        }
    }

    /**
     * 只读快照（诊断用）
     */
    public Map<String, Map<String, String>> snapshot() {
        synchronized (LOCK) {
            Map<String, Map<String, String>> all = readAll();
            return Collections.unmodifiableMap(all);
        }
    }
}
