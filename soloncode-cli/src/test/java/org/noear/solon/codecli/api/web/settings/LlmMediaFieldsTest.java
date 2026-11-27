package org.noear.solon.codecli.api.web.settings;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.config.entity.ModelDo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型设置的输入类型（inputModalities）规范化
 */
public class LlmMediaFieldsTest {
    @Test
    void modalitiesAreKeptAsIs() {
        // 空白与大小写在 ChatConfig.acceptsInput 比较时处理，设置接口不改写
        ModelDo config = new ModelDo();
        config.setInputModalities(Arrays.asList("text", "Image"));
        LlmSettingController.normalizeInputModalities(config);
        assertEquals(Arrays.asList("text", "Image"), config.getInputModalities());
        assertTrue(config.acceptsInput("image"));
    }

    @Test
    void emptyValuesClearToDefault() {
        ModelDo config = new ModelDo();
        config.setInputModalities(Collections.emptyList());
        LlmSettingController.normalizeInputModalities(config);
        assertNull(config.getInputModalities());

        config.setInputModalities(Arrays.asList(" ", ""));
        LlmSettingController.normalizeInputModalities(config);
        assertNull(config.getInputModalities());
    }

    @Test
    void webFormHasTheField() throws Exception {
        String html = new String(Files.readAllBytes(Paths.get("src/main/resources/static/web.html")), StandardCharsets.UTF_8);
        assertTrue(html.contains("id=\"llmInputModalities\""));
        assertFalse(html.contains("llmToolResultMedia"));
        String js = new String(Files.readAllBytes(Paths.get("src/main/resources/static/js/app-settings-llm.js")), StandardCharsets.UTF_8);
        assertTrue(js.contains("bodyObj.inputModalities"));
        assertFalse(js.contains("toolResultMedia"));
    }
}
