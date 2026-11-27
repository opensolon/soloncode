package org.noear.solon.codecli.model.discovery;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.codecli.model.discovery.adapter.AnthropicModelsAdapter;
import org.noear.solon.codecli.model.discovery.adapter.OllamaModelsAdapter;
import org.noear.solon.codecli.model.discovery.adapter.OpenAIModelsAdapter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 各适配器从供应商接口读出可输入类型（本地 HttpServer 模拟接口）
 */
public class ModelsAdapterModalitiesTest {
    private HttpServer server;
    private final Map<String, String> queries = new ConcurrentHashMap<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 按路径返回固定响应；/api/show 按请求体里的 model 名返回 */
    private String start(Map<String, String> routes) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            queries.put(path, String.valueOf(ex.getRequestURI().getQuery()));
            String key = path;
            if ("/api/show".equals(path)) {
                key = path + "#" + ONode.ofJson(readAll(ex.getRequestBody())).get("model").getString();
            }
            String body = routes.get(key);
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static ModelInfo byId(List<ModelInfo> list, String id) {
        for (ModelInfo m : list) {
            if (id.equals(m.getId())) {
                return m;
            }
        }
        return null;
    }

    @Test
    void anthropicReadsImageInputAndAsksForAllPages() throws Exception {
        Map<String, String> routes = new HashMap<>();
        routes.put("/v1/models", "{\"data\":["
                + "{\"id\":\"claude-sonnet-4-5\",\"type\":\"model\",\"capabilities\":{\"image_input\":{\"supported\":true},\"pdf_input\":{\"supported\":true}}},"
                + "{\"id\":\"claude-text\",\"type\":\"model\",\"capabilities\":{\"image_input\":{\"supported\":false}}},"
                + "{\"id\":\"claude-old\",\"type\":\"model\"}"
                + "]}");
        String base = start(routes);

        List<ModelInfo> list = new AnthropicModelsAdapter().fetchModels("test", base, null, "k");

        assertEquals("limit=1000", queries.get("/v1/models"));
        assertEquals(Arrays.asList("text", "image"), byId(list, "claude-sonnet-4-5").getInputModalities());
        assertEquals(Arrays.asList("text"), byId(list, "claude-text").getInputModalities());
        // 没给 capabilities：不知道，交给 models.dev
        assertNull(byId(list, "claude-old").getInputModalities());
    }

    @Test
    void ollamaUsesShowCapabilities() throws Exception {
        Map<String, String> routes = new HashMap<>();
        routes.put("/api/tags", "{\"models\":["
                + "{\"name\":\"llama3.2-vision:latest\",\"model\":\"llama3.2-vision:latest\"},"
                + "{\"name\":\"qwen3:8b\",\"model\":\"qwen3:8b\"},"
                + "{\"name\":\"nomic-embed-text:latest\",\"model\":\"nomic-embed-text:latest\"},"
                + "{\"name\":\"proxied:latest\",\"model\":\"proxied:latest\"}"
                + "]}");
        routes.put("/api/show#llama3.2-vision:latest",
                "{\"capabilities\":[\"completion\",\"vision\"],\"model_info\":{\"mllama.context_length\":131072}}");
        routes.put("/api/show#qwen3:8b",
                "{\"capabilities\":[\"completion\",\"tools\",\"thinking\"],\"model_info\":{\"general.architecture\":\"qwen3\",\"qwen3.context_length\":40960}}");
        routes.put("/api/show#nomic-embed-text:latest", "{\"capabilities\":[\"embedding\"]}");
        // proxied 没有 /api/show（404）
        String base = start(routes);

        List<ModelInfo> list = new OllamaModelsAdapter().fetchModels("test", base, null, null);

        ModelInfo vision = byId(list, "llama3.2-vision:latest");
        assertEquals(Arrays.asList("text", "image"), vision.getInputModalities());
        assertEquals(Long.valueOf(131072), vision.getMaxInputTokens());

        ModelInfo qwen = byId(list, "qwen3:8b");
        assertEquals(Arrays.asList("text"), qwen.getInputModalities());
        assertEquals(Long.valueOf(40960), qwen.getMaxInputTokens());

        // 嵌入模型不能对话，不列出（同 opencode）
        assertNull(byId(list, "nomic-embed-text:latest"));

        // 查不到能力的仍然列出，只是不带可输入类型
        ModelInfo proxied = byId(list, "proxied:latest");
        assertNotNull(proxied);
        assertNull(proxied.getInputModalities());
        assertEquals(3, list.size());
    }

    @Test
    void openaiCompatibleReadsOptionalModalities() throws Exception {
        Map<String, String> routes = new HashMap<>();
        routes.put("/v1/models", "{\"data\":["
                + "{\"id\":\"openai/gpt-4o\",\"architecture\":{\"input_modalities\":[\"text\",\"image\",\"file\"]},\"context_length\":128000},"
                + "{\"id\":\"modal-model\",\"input_modalities\":[\"text\"]},"
                + "{\"id\":\"deepseek-chat\",\"object\":\"model\",\"owned_by\":\"deepseek\"}"
                + "]}");
        String base = start(routes);

        List<ModelInfo> list = new OpenAIModelsAdapter().fetchModels("test", base + "/v1", null, "k");

        ModelInfo router = byId(list, "openai/gpt-4o");
        assertEquals(Arrays.asList("text", "image"), router.getInputModalities());
        assertEquals(Long.valueOf(128000), router.getMaxInputTokens());
        // 只认 OpenRouter 的 architecture.input_modalities，顶层字段不读
        assertNull(byId(list, "modal-model").getInputModalities());
        // 标准 OpenAI 格式没有能力信息
        ModelInfo plain = byId(list, "deepseek-chat");
        assertNull(plain.getInputModalities());
        assertEquals("deepseek", plain.getOwnedBy());
    }
}
