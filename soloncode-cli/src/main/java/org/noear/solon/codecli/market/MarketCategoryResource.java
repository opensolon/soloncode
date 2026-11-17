package org.noear.solon.codecli.market;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 市场分类的内置资源加载器。
 *
 * <p>各市场的分类清单都是源站的<b>静态内容</b>（不是随请求变化的数据），运行期反复抓取只会
 * 让分类栏受制于源站改版：例如 ClawHub 的分类表根本不在接口里，而在前端打包的常量文件
 * （{@code catalogMetadata-<hash>.js}）中，只能靠解析 SSR HTML 逐层跟 hash 文件名；
 * 腾讯云与 SkillHub 虽然有分类接口，但也只是把同一份静态清单多请求一次。</p>
 *
 * <p>因此分类清单统一内置为随包的版本化 JSON 资源（{@code *-categories.json}），
 * 与适配器类同目录，运行期零网络请求。源站增删分类时按需更新该文件即可，
 * 文件里的 {@code version}/{@code snapshotAt}/{@code source} 用于追溯快照来源。</p>
 *
 * <p>资源结构：</p>
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "version": "2026-11-17",
 *   "snapshotAt": "2026-11-17",
 *   "source": "接口或页面地址",
 *   "categories": [ { "id": "...", "name": "..." } ]
 * }
 * </pre>
 *
 * <p>读取失败（资源缺失、JSON 非法、结构不符）时记 warn 并返回空列表——分类栏是辅助功能，
 * 空列表会让前端隐藏分类栏，但不影响列表与搜索。</p>
 *
 * @author noear 2026/11/17 created
 */
public final class MarketCategoryResource {
    private static final Logger LOG = LoggerFactory.getLogger(MarketCategoryResource.class);

    /** 资源结构版本，结构变更时递增 */
    private static final int SCHEMA_VERSION = 1;

    private static final Map<String, List<Entry>> CACHE = new HashMap<>();

    private MarketCategoryResource() {
    }

    /**
     * 分类条目（id 与展示名）。
     *
     * <p>展示名属于市场的动态内容，<b>原样透出、不做翻译</b>（不同市场语种不同）。</p>
     */
    public static final class Entry {
        private final String id;
        private final String name;

        Entry(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    /**
     * 读取内置分类资源（结果按资源缓存，不重复读文件）。
     *
     * @param anchor   适配器类，资源与其同目录
     * @param resource 资源文件名（如 {@code clawhub-categories.json}）
     */
    public static List<Entry> load(Class<?> anchor, String resource) {
        String key = anchor.getName() + "/" + resource;

        synchronized (CACHE) {
            List<Entry> cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }

            List<Entry> list = read(anchor, resource);
            CACHE.put(key, list);
            return list;
        }
    }

    private static List<Entry> read(Class<?> anchor, String resource) {
        String json;
        try (InputStream in = anchor.getResourceAsStream(resource)) {
            if (in == null) {
                LOG.warn("缺少市场分类资源 {}（{}），分类栏将不显示", resource, anchor.getName());
                return Collections.emptyList();
            }
            json = readAll(in);
        } catch (IOException e) {
            LOG.warn("读取市场分类资源 {} 失败: {}", resource, e.getMessage());
            return Collections.emptyList();
        }

        try {
            ONode root = ONode.ofJson(json);

            int schemaVersion = root.get("schemaVersion") == null ? SCHEMA_VERSION : root.get("schemaVersion").getInt();
            if (schemaVersion != SCHEMA_VERSION) {
                LOG.warn("市场分类资源 {} 的结构版本为 {}，当前实现按 {} 解析", resource, schemaVersion, SCHEMA_VERSION);
            }

            ONode itemsNode = root.get("categories");
            if (itemsNode == null || itemsNode.isArray() == false) {
                LOG.warn("市场分类资源 {} 缺少 categories 数组", resource);
                return Collections.emptyList();
            }

            List<Entry> list = new ArrayList<>();
            for (ONode node : itemsNode.getArray()) {
                String id = text(node, "id");
                String name = text(node, "name");

                if (id == null || name == null) {
                    LOG.warn("市场分类资源 {} 有条目缺少 id/name，已跳过", resource);
                    continue;
                }
                list.add(new Entry(id, name));
            }

            return Collections.unmodifiableList(list);
        } catch (Exception e) {
            LOG.warn("解析市场分类资源 {} 失败: {}", resource, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String text(ONode node, String key) {
        ONode child = node.get(key);
        if (child == null || child.isNull()) {
            return null;
        }
        String value = child.getString();
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int count;
        while ((count = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, count);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
