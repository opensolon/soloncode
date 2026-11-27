package org.noear.solon.codecli.model.discovery;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * models.json 查找与可输入类型（离线数据，不联网）
 */
public class ModelSpecServiceTest {
    private static final String JSON = "{"
            + "\"deepseek/deepseek-chat\":{\"id\":\"deepseek/deepseek-chat\",\"name\":\"DeepSeek Chat\","
            + "\"modalities\":{\"input\":[\"text\"],\"output\":[\"text\"]},\"limit\":{\"context\":128000,\"output\":8192}},"
            + "\"anthropic/claude-sonnet-4-5-20250929\":{\"id\":\"anthropic/claude-sonnet-4-5-20250929\",\"name\":\"Claude Sonnet 4.5\","
            + "\"modalities\":{\"input\":[\"text\",\"image\",\"pdf\"],\"output\":[\"text\"]},\"limit\":{\"context\":200000}},"
            + "\"google/gemini-2.5-flash\":{\"id\":\"google/gemini-2.5-flash\",\"name\":\"Gemini 2.5 Flash\","
            + "\"modalities\":{\"input\":[\"text\",\"image/png\",\"audio\",\"video\",\"application/pdf\"]},\"limit\":{\"context\":1048576}},"
            + "\"x/no-modalities\":{\"id\":\"x/no-modalities\",\"name\":\"Bare\",\"limit\":{\"context\":4096}}"
            + "}";

    private final ModelSpecService service = ModelSpecService.of(JSON);

    @Test
    void bareModelIdMatchesLastSegment() {
        // 以前拿裸 id 查「厂商/模型」键查不到，上下文长度也一直没补上
        assertEquals(Long.valueOf(128000), service.getContextLength("deepseek-chat"));
        assertEquals(Arrays.asList("text"), service.getInputModalities("deepseek-chat"));
        assertEquals(Arrays.asList("text", "image"), service.getInputModalities("claude-sonnet-4-5-20250929"));
    }

    @Test
    void fullKeyAndCaseInsensitive() {
        assertEquals(Arrays.asList("text", "image"), service.getInputModalities("anthropic/claude-sonnet-4-5-20250929"));
        assertEquals(Arrays.asList("text"), service.getInputModalities("DeepSeek-Chat"));
        // 别的厂商前缀（如 OpenRouter 的 id）也按最后一段命中
        assertEquals(Arrays.asList("text"), service.getInputModalities("someone/deepseek-chat"));
    }

    @Test
    void displayNameIsLastResort() {
        assertEquals(Long.valueOf(200000), service.getContextLength("Claude Sonnet 4.5"));
    }

    @Test
    void modalitiesAreNormalized() {
        // MIME 取大类，pdf 去掉（没有文档类媒体）
        assertEquals(Arrays.asList("text", "image", "audio", "video"), service.getInputModalities("gemini-2.5-flash"));
    }

    @Test
    void unknownReturnsNull() {
        assertNull(service.getInputModalities("kimi-k2-0905-preview"));
        assertNull(service.getContextLength("kimi-k2-0905-preview"));
        assertNull(service.getInputModalities(""));
        assertNull(service.getInputModalities(null));
        // 有条目但没写 modalities：不知道，保持不限制
        assertNull(service.getInputModalities("no-modalities"));
        assertEquals(Long.valueOf(4096), service.getContextLength("no-modalities"));
    }

    @Test
    void returnedListIsACopy() {
        service.getInputModalities("deepseek-chat").add("image");
        assertEquals(Arrays.asList("text"), service.getInputModalities("deepseek-chat"));
    }

    @Test
    void modelModalitiesNormalize() {
        assertNull(ModelModalities.normalize(null));
        assertNull(ModelModalities.normalize(Arrays.asList("pdf", "application/pdf", " ")));
        assertEquals(Arrays.asList("text", "image"), ModelModalities.normalize(Arrays.asList("Text", "image/jpeg", "image", "file")));
        assertEquals(Arrays.asList("text"), ModelModalities.ofVision(false));
        assertEquals(Arrays.asList("text", "image"), ModelModalities.ofVision(true));
    }
}
