package org.noear.solon.codecli.portal.web.models;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.config.models.ModelsAdapter;
import org.noear.solon.codecli.config.models.ModelsAdapterManager;
import org.noear.solon.codecli.config.models.adapter.GoogleModelsAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GoogleModelsAdapter and manager integration.
 */
public class GoogleModelsAdapterTest {

    @Test
    public void testDeriveBaseUrl() {
        GoogleModelsAdapter adapter = new GoogleModelsAdapter();

        assertEquals("https://generativelanguage.googleapis.com",
                adapter.deriveBaseUrl("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"));

        assertEquals("https://generativelanguage.googleapis.com",
                adapter.deriveBaseUrl("https://generativelanguage.googleapis.com/v1beta/models"));

        assertEquals("https://generativelanguage.googleapis.com",
                adapter.deriveBaseUrl("https://generativelanguage.googleapis.com/v1beta"));

        assertEquals("https://generativelanguage.googleapis.com",
                adapter.deriveBaseUrl("https://generativelanguage.googleapis.com"));
    }

    @Test
    public void testBuildModelsUrl() {
        GoogleModelsAdapter adapter = new GoogleModelsAdapter();

        assertEquals("https://generativelanguage.googleapis.com/v1beta/models",
                adapter.buildModelsUrl("https://generativelanguage.googleapis.com"));

        assertEquals("https://generativelanguage.googleapis.com/v1beta/models",
                adapter.buildModelsUrl("https://generativelanguage.googleapis.com/v1beta"));

        assertEquals("https://my-proxy.com/v1/models",
                adapter.buildModelsUrl("https://my-proxy.com/v1"));
    }

    @Test
    public void testModelsAdapterManagerResolvesGoogle() {
        ModelsAdapter adapter = ModelsAdapterManager.getInstance().getAdapter("google");
        assertNotNull(adapter);
        assertTrue(adapter instanceof GoogleModelsAdapter);

        ModelsAdapter adapter2 = ModelsAdapterManager.getInstance().getAdapter("google-models");
        assertNotNull(adapter2);
        assertTrue(adapter2 instanceof GoogleModelsAdapter);

        ModelsAdapter adapter3 = ModelsAdapterManager.getInstance().getAdapter("gemini");
        assertNotNull(adapter3);
        assertTrue(adapter3 instanceof GoogleModelsAdapter);
    }
}
