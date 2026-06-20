package org.noear.solon.codecli.portal.web.model;

import org.noear.snack4.ONode;
import org.noear.solon.net.http.HttpUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型规格参考服务
 * 从 models.json 读取模型规格信息，作为备用数据源
 */
public class ModelSpecService {
    
    private final Map<String, ModelSpec> specs = new ConcurrentHashMap<>();
    
    public ModelSpecService() {
        loadSpecs();
    }
    
    private void loadSpecs() {
        try {
            String json = HttpUtils.http("https://models.dev/models.json").get();
            if (json != null) {
                ONode root = ONode.ofJson(json);
                // 转换为 Map<String, ONode> 遍历
                Map<String, ONode> map = root.toBean(Map.class);
                if (map != null) {
                    for (Map.Entry<String, ONode> entry : map.entrySet()) {
                        String key = entry.getKey();
                        ONode node = entry.getValue();
                        
                        ModelSpec spec = new ModelSpec();
                        spec.setId(node.get("id").getString());
                        spec.setName(node.get("name").getString());
                        
                        ONode limit = node.get("limit");
                        if (limit != null) {
                            spec.setContext(limit.get("context").getLong());
                            spec.setInput(limit.get("input").getLong());
                            spec.setOutput(limit.get("output").getLong());
                        }
                        
                        specs.put(key, spec);
                    }
                }
            }
        } catch (Throwable e) {
            // 忽略加载失败
        }
    }
    
    /**
     * 根据模型ID获取上下文大小
     * 支持多种格式：gpt-5, openai/gpt-5
     */
    public Long getContextLength(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return null;
        }

        // 尝试直接匹配
        ModelSpec spec = specs.get(modelId);
        if (spec != null && spec.getContext() > 0) {
            return spec.getContext();
        }

        for (ModelSpec spec1 : specs.values()) {
            if (spec1.getName().equalsIgnoreCase(modelId)) {
                return spec1.context;
            }
        }

        return null;
    }
    
    /**
     * 模型规格
     */
    public static class ModelSpec {
        private String id;
        private String name;
        private long context;
        private long input;
        private long output;
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public long getContext() { return context; }
        public void setContext(long context) { this.context = context; }
        public long getInput() { return input; }
        public void setInput(long input) { this.input = input; }
        public long getOutput() { return output; }
        public void setOutput(long output) { this.output = output; }
    }
}
