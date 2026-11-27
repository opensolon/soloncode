package org.noear.solon.codecli.model.discovery.adapter;

import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.codecli.model.discovery.ModelApiUrl;
import org.noear.solon.codecli.model.discovery.ModelInfo;
import org.noear.solon.codecli.model.discovery.ModelModalities;
import org.noear.solon.codecli.model.discovery.ModelsAdapter;
import org.noear.solon.codecli.model.discovery.ModelsFetchException;
import org.noear.solon.codecli.model.discovery.ModelsFetchReason;
import org.noear.solon.codecli.model.discovery.ModelsHttp;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Ollama 协议实现
 * 接口：GET {baseUrl}/api/tags，再逐个 POST {baseUrl}/api/show 取能力（同 opencode plugin/provider/ollama.ts）：
 * <ul>
 *     <li>capabilities 含 vision → 可输入 text,image，否则 text</li>
 *     <li>capabilities 不含 completion（如嵌入模型）→ 不列出</li>
 *     <li>model_info 中 *.context_length → 上下文长度</li>
 * </ul>
 * /api/show 失败或超时的模型仍按 /api/tags 列出（不带能力），不像 opencode 那样整个丢掉：
 * 远程 Ollama 网关常常只转发 /api/tags。
 */
@Slf4j
public class OllamaModelsAdapter implements ModelsAdapter {
    /** /api/show 并发数（同 opencode） */
    private static final int SHOW_CONCURRENCY = 4;
    /** 所有 /api/show 的总等待时长（秒）：拉取接口要在前端 20 秒超时前返回 */
    private static final int SHOW_BUDGET_SECONDS = 6;

    @Override
    public String getStandard() {
        return "ollama";
    }

    @Override
    public String deriveBaseUrl(String apiUrl) {
        return ModelApiUrl.stripSuffixes(
                ModelApiUrl.trimTrailingSlash(apiUrl == null ? "" : apiUrl.trim()),
                "/api/chat", "/api/generate", "/api/tags",
                "/v1/chat/completions", "/chat/completions");
    }

    @Override
    public List<ModelInfo> fetchModels(String userAgent, String baseUrl, Map<String, String> headers, String apiKey) {
        String modelsUrl = baseUrl + "/api/tags";
        // 地址非法时立即失败，不必白等一轮连接超时
        ModelsHttp.requireHttpUrl(modelsUrl, "Ollama");
        List<ModelInfo> result = new ArrayList<>();

        try {
            HttpUtils http = ModelsHttp.create(modelsUrl, userAgent);
            applyAuth(http, headers, apiKey);

            String body = ModelsHttp.getBody(http, "Ollama");

            ONode root = ModelsHttp.parseJson(body, "Ollama");
            ONode models = root.get("models");
            if (!models.isArray()) {
                // 状态码正常但结构不是模型列表，多为地址或协议选错
                throw new ModelsFetchException("Ollama model list response has no models array",
                        ModelsFetchReason.INVALID_RESPONSE, 200, null);
            }
            for (int i = 0; i < models.size(); i++) {
                ONode item = models.get(i);
                String name = item.get("name").getString();
                long created = System.currentTimeMillis() / 1000;
                // 不能用 exists()：它按 JsonPath 解析，不以 $ 开头会抛异常，整个拉取就失败了
                if (item.hasKey("modified_at")) {
                    try {
                        created = java.time.Instant.parse(item.get("modified_at").getString()).getEpochSecond();
                    } catch (Exception ignored) {
                    }
                }
                result.add(ModelInfo.builder()
                        .id(name)
                        .object("model")
                        .created(created)
                        .ownedBy("ollama")
                        .type("chat")
                        .build());
            }
        } catch (ModelsFetchException e) {
            log.warn("[Ollama] Failed to fetch model list: reason={}, status={}", e.getReason(), e.getStatus());
            throw e;
        } catch (Exception e) {
            log.warn("[Ollama] Failed to fetch model list");
            throw new ModelsFetchException("Ollama model list request failed", ModelsFetchReason.UNKNOWN, e);
        }

        return showAll(result, userAgent, baseUrl, headers, apiKey);
    }

    /**
     * 并发查 /api/show 补能力；不支持补全的模型（嵌入模型等）去掉
     */
    private List<ModelInfo> showAll(List<ModelInfo> models, String userAgent, String baseUrl,
                                    Map<String, String> headers, String apiKey) {
        if (models.isEmpty()) {
            return models;
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(SHOW_CONCURRENCY, models.size()), r -> {
            Thread t = new Thread(r, "ollama-show");
            t.setDaemon(true);
            return t;
        });

        try {
            List<Future<ONode>> futures = new ArrayList<>();
            for (ModelInfo model : models) {
                futures.add(pool.submit(() -> show(userAgent, baseUrl, headers, apiKey, model.getId())));
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHOW_BUDGET_SECONDS);
            List<ModelInfo> result = new ArrayList<>();
            for (int i = 0; i < models.size(); i++) {
                ModelInfo model = models.get(i);
                ONode show = null;
                try {
                    Future<ONode> future = futures.get(i);
                    long left = deadline - System.nanoTime();
                    if (left > 0) {
                        show = future.get(left, TimeUnit.NANOSECONDS);
                    } else if (future.isDone()) {
                        // 预算用完后，已经返回的结果照样用
                        show = future.get();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // 单个模型查不到能力不影响列表
                }

                if (apply(model, show)) {
                    result.add(model);
                }
            }
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 用 /api/show 的结果补能力；返回 false 表示该模型不支持补全，不列出
     */
    static boolean apply(ModelInfo model, ONode show) {
        if (show == null || show.isObject() == false) {
            return true;
        }

        ONode capabilities = show.get("capabilities");
        if (capabilities.isArray()) {
            boolean completion = false;
            boolean vision = false;
            for (ONode c : capabilities.getArray()) {
                String v = c.getString();
                if ("completion".equals(v)) {
                    completion = true;
                } else if ("vision".equals(v)) {
                    vision = true;
                }
            }
            if (completion == false) {
                return false;
            }
            model.setInputModalities(ModelModalities.ofVision(vision));
        }

        ONode modelInfo = show.get("model_info");
        if (modelInfo.isObject()) {
            for (Map.Entry<String, ONode> e : modelInfo.getObject().entrySet()) {
                if (e.getKey().endsWith(".context_length") && e.getValue().isNumber()) {
                    long contextLength = e.getValue().getLong();
                    if (contextLength > 0) {
                        model.setMaxInputTokens(contextLength);
                        break;
                    }
                }
            }
        }

        return true;
    }

    private ONode show(String userAgent, String baseUrl, Map<String, String> headers, String apiKey, String model) throws Exception {
        HttpUtils http = HttpUtils.http(baseUrl + "/api/show")
                .userAgent(userAgent)
                .timeout(ModelsHttp.CONNECT_TIMEOUT_SECONDS, ModelsHttp.WRITE_TIMEOUT_SECONDS, SHOW_BUDGET_SECONDS);
        applyAuth(http, headers, apiKey);
        http.bodyOfJson(new ONode().set("model", model).toJson());

        try (HttpResponse resp = http.exec("POST")) {
            if (resp.code() < 200 || resp.code() >= 300) {
                return null;
            }
            return ONode.ofJson(resp.bodyAsString());
        }
    }

    private void applyAuth(HttpUtils http, Map<String, String> headers, String apiKey) {
        ProxyConfig.applyIfNeeded(http);

        if (headers != null) {
            headers.forEach(http::header);
        }
        if (apiKey != null && !apiKey.isEmpty()) {
            http.header("Authorization", "Bearer " + apiKey);
        }
    }
}
