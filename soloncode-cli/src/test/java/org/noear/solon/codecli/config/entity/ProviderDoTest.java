package org.noear.solon.codecli.config.entity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProviderDo} custom headers.
 *
 * <p>Provider-level headers exist so interfaces that require a header outside
 * the standard's own scheme can be configured at all — for example opencode's
 * {@code x-opencode-session}, which the web form previously had no field for.</p>
 */
public class ProviderDoTest {
    @Test
    public void headers_defaultToEmptyNotNull() {
        ProviderDo provider = new ProviderDo();

        assertNotNull(provider.getHeaders());
        assertTrue(provider.getHeaders().isEmpty());
    }

    @Test
    public void headers_keepSuppliedOrder() {
        ProviderDo provider = new ProviderDo();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-opencode-session", "session-1");
        headers.put("X-Custom-Header", "value");
        provider.setHeaders(headers);

        assertEquals(2, provider.getHeaders().size());
        assertEquals("session-1", provider.getHeaders().get("x-opencode-session"));
        assertEquals("value", provider.getHeaders().get("X-Custom-Header"));
    }

    @Test
    public void headers_areIndependentPerInstance() {
        ProviderDo first = new ProviderDo();
        first.getHeaders().put("x-opencode-session", "session-1");

        ProviderDo second = new ProviderDo();

        assertTrue(second.getHeaders().isEmpty());
    }

    @Test
    public void otherFieldsUnchangedByHeadersSupport() {
        ProviderDo provider = new ProviderDo();
        provider.setName("opencode");
        provider.setStandard("openai");
        provider.setApiUrl("https://api.example.com/v1");
        provider.setApiKey("sk-test");

        assertEquals("opencode", provider.getName());
        assertEquals("openai", provider.getStandard());
        assertEquals("https://api.example.com/v1", provider.getApiUrl());
        assertEquals("sk-test", provider.getApiKey());
        assertTrue(provider.isEnabled());
    }
}
