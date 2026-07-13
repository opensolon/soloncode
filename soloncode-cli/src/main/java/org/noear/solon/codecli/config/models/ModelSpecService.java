package org.noear.solon.codecli.config.models;

import org.noear.snack4.ONode;
import org.noear.solon.net.http.HttpUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 models.json 读取模型规格信息，作为备用数据源。
 */
public class ModelSpecService {
    private static final String MODELS_SPEC_URL = "https://models.dev/models.json";

    private final Map<String, ModelSpec> specs = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public Long getContextLength(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return null;
        }

        ensureLoaded();

        ModelSpec spec = specs.get(modelId);
        if (spec != null && spec.getContext() > 0) {
            return spec.getContext();
        }

        for (ModelSpec item : specs.values()) {
            if (item.getName() != null && item.getName().equalsIgnoreCase(modelId) && item.getContext() > 0) {
                return item.getContext();
            }
        }

        return null;
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
                String json = HttpUtils.http(MODELS_SPEC_URL).timeout(5).get();
                if (json != null && json.isEmpty() == false) {
                    ONode root = ONode.ofJson(json);
                    for (Map.Entry<String, ONode> entry : root.getObject().entrySet()) {
                        ONode node = entry.getValue();

                        ModelSpec spec = new ModelSpec();
                        spec.setId(node.get("id").getString(entry.getKey()));
                        spec.setName(node.get("name").getString());

                        ONode limit = node.get("limit");
                        if (limit != null) {
                            spec.setContext(limit.get("context").getLong());
                            spec.setInput(limit.get("input").getLong());
                            spec.setOutput(limit.get("output").getLong());
                        }

                        specs.put(entry.getKey(), spec);
                        if (spec.getId() != null && spec.getId().isEmpty() == false) {
                            specs.putIfAbsent(spec.getId(), spec);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Ignore remote spec loading failures and keep local fallbacks working.
            } finally {
                loaded = true;
            }
        }
    }

    public static class ModelSpec {
        private String id;
        private String name;
        private long context;
        private long input;
        private long output;

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
    }
}
