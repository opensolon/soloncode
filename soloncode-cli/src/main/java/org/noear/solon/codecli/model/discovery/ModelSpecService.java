package org.noear.solon.codecli.model.discovery;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.net.http.HttpUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 models.json 读取模型规格信息，作为备用数据源。
 * <p>
 * models.json 的键是「厂商/模型」（如 openai/gpt-4o、anthropic/claude-sonnet-4-5-20250929），
 * 而供应商接口返回的是裸模型 id，所以按「完整键 → 键的最后一段 → 显示名」依次查找，都不区分大小写。
 */
public class ModelSpecService {
    private static ModelSpecService instance;

    public static ModelSpecService getInstance() {
        if (instance == null) {
            instance = new ModelSpecService();
        }

        return instance;
    }

    /**
     * 用给定的 models.json 内容构建（不联网，供测试使用）
     */
    static ModelSpecService of(String json) {
        ModelSpecService service = new ModelSpecService();
        service.load(json);
        service.loaded = true;
        return service;
    }

    private ModelSpecService() {

    }

    //-------

    private static final String MODELS_SPEC_URL = "https://models.dev/models.json";

    /** 完整键（小写）→ 规格 */
    private final Map<String, ModelSpec> byKey = new ConcurrentHashMap<>();
    /** 键的最后一段（小写）→ 规格；同名时保留先出现的 */
    private final Map<String, ModelSpec> byTail = new ConcurrentHashMap<>();
    /** 显示名（小写）→ 规格；同名时保留先出现的 */
    private final Map<String, ModelSpec> byName = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public Long getContextLength(String modelId) {
        ModelSpec spec = find(modelId);
        if (spec != null && spec.getContext() > 0) {
            return spec.getContext();
        }

        return null;
    }

    /**
     * 查询模型的可输入类型（已归一化为 text/image/audio/video）；查不到返回 null
     */
    public List<String> getInputModalities(String modelId) {
        ModelSpec spec = find(modelId);
        if (spec == null || spec.getInputModalities() == null) {
            return null;
        }

        return new ArrayList<>(spec.getInputModalities());
    }

    ModelSpec find(String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) {
            return null;
        }

        ensureLoaded();

        String id = modelId.trim().toLowerCase(Locale.ROOT);

        ModelSpec spec = byKey.get(id);
        if (spec == null) {
            spec = byTail.get(tailOf(id));
        }
        if (spec == null) {
            spec = byName.get(id);
        }

        return spec;
    }

    private static String tailOf(String key) {
        int idx = key.lastIndexOf('/');
        return idx < 0 ? key : key.substring(idx + 1);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }

        synchronized (this) {
            if (loaded) {
                return;
            }

            try {
                HttpUtils http = HttpUtils.http(MODELS_SPEC_URL).timeout(5);
                ProxyConfig.applyIfNeeded(http);
                load(http.get());
            } catch (Throwable ignored) {
                // Ignore remote spec loading failures and keep local fallbacks working.
            } finally {
                loaded = true;
            }
        }
    }

    private void load(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }

        ONode root = ONode.ofJson(json);
        if (root.isObject() == false) {
            return;
        }

        for (Map.Entry<String, ONode> entry : root.getObject().entrySet()) {
            ONode node = entry.getValue();
            if (node.isObject() == false) {
                continue;
            }

            ModelSpec spec = new ModelSpec();
            spec.setId(node.get("id").getString(entry.getKey()));
            spec.setName(node.get("name").getString());

            ONode limit = node.get("limit");
            if (limit != null) {
                spec.setContext(limit.get("context").getLong());
                spec.setInput(limit.get("input").getLong());
                spec.setOutput(limit.get("output").getLong());
            }

            spec.setInputModalities(ModelModalities.of(node.get("modalities").get("input")));

            index(entry.getKey(), spec);
            if (spec.getId() != null && spec.getId().isEmpty() == false) {
                index(spec.getId(), spec);
            }
            if (spec.getName() != null && spec.getName().isEmpty() == false) {
                byName.putIfAbsent(spec.getName().trim().toLowerCase(Locale.ROOT), spec);
            }
        }
    }

    private void index(String key, ModelSpec spec) {
        String k = key.trim().toLowerCase(Locale.ROOT);
        byKey.putIfAbsent(k, spec);
        byTail.putIfAbsent(tailOf(k), spec);
    }

    public static class ModelSpec {
        private String id;
        private String name;
        private long context;
        private long input;
        private long output;
        private List<String> inputModalities;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public long getContext() {
            return context;
        }

        public void setContext(long context) {
            this.context = context;
        }

        public long getInput() {
            return input;
        }

        public void setInput(long input) {
            this.input = input;
        }

        public long getOutput() {
            return output;
        }

        public void setOutput(long output) {
            this.output = output;
        }

        public List<String> getInputModalities() {
            return inputModalities;
        }

        public void setInputModalities(List<String> inputModalities) {
            this.inputModalities = inputModalities;
        }
    }
}
