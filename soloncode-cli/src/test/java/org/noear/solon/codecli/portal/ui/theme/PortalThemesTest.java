package org.noear.solon.codecli.portal.ui.theme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PortalThemesTest {
    @AfterEach
    public void cleanup() {
        PortalThemes.replaceCustomThemes(null);
    }

    @Test
    public void shouldResolveCustomThemeWithInheritance() {
        Map<String, Map<String, String>> specs = new LinkedHashMap<String, Map<String, String>>();

        Map<String, String> base = new LinkedHashMap<String, String>();
        base.put("extends", "solon");
        base.put("accent", "#112233");
        base.put("toolResult", "rgb(1, 2, 3)");
        specs.put("team-base", base);

        Map<String, String> review = new LinkedHashMap<String, String>();
        review.put("parent", "team-base");
        review.put("assistantTitle", "10,20,30");
        review.put("thinkingBorder", "#abc");
        specs.put("team-review", review);

        PortalThemes.replaceCustomThemes(specs);

        PortalTheme resolved = PortalThemes.find("TEAM-REVIEW");
        assertNotNull(resolved);
        assertTrue(PortalThemes.isCustom("team-review"));

        assertEquals(17, resolved.accent().r());
        assertEquals(34, resolved.accent().g());
        assertEquals(51, resolved.accent().b());

        assertEquals(10, resolved.assistantTitle().r());
        assertEquals(20, resolved.assistantTitle().g());
        assertEquals(30, resolved.assistantTitle().b());

        assertEquals(170, resolved.thinkingBorder().r());
        assertEquals(187, resolved.thinkingBorder().g());
        assertEquals(204, resolved.thinkingBorder().b());

        assertEquals(1, resolved.toolResult().r());
        assertEquals(2, resolved.toolResult().g());
        assertEquals(3, resolved.toolResult().b());
    }

    @Test
    public void shouldExposeSupportedKeys() {
        assertTrue(PortalThemes.supportedKeys().contains("toolPreview"));
        assertTrue(PortalThemes.supportedKeys().contains("markdownCodeBorder"));
        assertFalse(PortalThemes.supportedKeys().isEmpty());
    }
}
