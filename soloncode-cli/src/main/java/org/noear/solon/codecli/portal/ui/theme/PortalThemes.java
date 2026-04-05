/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.portal.ui.theme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Portal UI 内置主题表。
 */
public final class PortalThemes {
    private static final Logger LOG = LoggerFactory.getLogger(PortalThemes.class);
    private static final List<String> SUPPORTED_KEYS = Collections.unmodifiableList(java.util.Arrays.asList(
            "accent",
            "accentStrong",
            "textPrimary",
            "textMuted",
            "textSoft",
            "success",
            "warning",
            "error",
            "separator",
            "userTitle",
            "assistantTitle",
            "thinkingTitle",
            "toolTitle",
            "blockTime",
            "thinkingBorder",
            "toolMeta",
            "toolValue",
            "toolResult",
            "toolPreview",
            "markdownHeader",
            "markdownBold",
            "markdownInlineCode",
            "markdownCodeText",
            "markdownCodeBorder",
            "markdownListBullet",
            "markdownListNumber",
            "markdownBlockquote",
            "markdownRule",
            "tableBorder",
            "tableHeader"
    ));
    private static final List<PortalTheme> BUILT_INS;
    private static final Map<String, PortalTheme> BUILT_IN_INDEX;
    private static final PortalTheme DEFAULT_THEME;
    private static volatile List<PortalTheme> customThemes = Collections.emptyList();
    private static volatile Map<String, PortalTheme> customThemeIndex = Collections.emptyMap();

    static {
        List<PortalTheme> themes = new ArrayList<PortalTheme>();
        themes.add(new PortalTheme(
                "solon",
                PortalColor.rgb(255, 125, 144),
                PortalColor.rgb(255, 154, 168),
                PortalColor.rgb(243, 245, 247),
                PortalColor.rgb(114, 123, 137),
                PortalColor.rgb(160, 168, 184),
                PortalColor.rgb(39, 201, 63),
                PortalColor.rgb(232, 194, 122),
                PortalColor.rgb(244, 124, 124),
                PortalColor.rgb(90, 96, 110),
                PortalColor.rgb(255, 186, 196),
                PortalColor.rgb(255, 154, 168),
                PortalColor.rgb(196, 205, 223),
                PortalColor.rgb(255, 125, 144),
                PortalColor.rgb(114, 123, 137),
                PortalColor.rgb(160, 168, 184),
                PortalColor.rgb(160, 168, 184),
                PortalColor.rgb(243, 245, 247),
                PortalColor.rgb(231, 241, 250),
                PortalColor.rgb(114, 123, 137),
                PortalColor.rgb(255, 125, 144),
                PortalColor.rgb(243, 245, 247),
                PortalColor.rgb(232, 194, 122),
                PortalColor.rgb(165, 214, 132),
                PortalColor.rgb(60, 65, 75),
                PortalColor.rgb(255, 125, 144),
                PortalColor.rgb(130, 170, 255),
                PortalColor.rgb(114, 123, 137),
                PortalColor.rgb(60, 65, 75),
                PortalColor.rgb(80, 90, 110),
                PortalColor.rgb(200, 210, 220)
        ));
        themes.add(new PortalTheme(
                "opencode",
                PortalColor.rgb(14, 165, 233),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(239, 246, 255),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(34, 197, 94),
                PortalColor.rgb(245, 158, 11),
                PortalColor.rgb(248, 113, 113),
                PortalColor.rgb(44, 77, 122),
                PortalColor.rgb(191, 219, 254),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(239, 246, 255),
                PortalColor.rgb(191, 219, 254),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(239, 246, 255),
                PortalColor.rgb(251, 191, 36),
                PortalColor.rgb(110, 231, 183),
                PortalColor.rgb(44, 77, 122),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(147, 197, 253),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(44, 77, 122),
                PortalColor.rgb(70, 100, 145),
                PortalColor.rgb(239, 246, 255)
        ));
        themes.add(new PortalTheme(
                "ocean",
                PortalColor.rgb(96, 165, 250),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(232, 241, 255),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(250, 204, 21),
                PortalColor.rgb(248, 113, 113),
                PortalColor.rgb(44, 69, 99),
                PortalColor.rgb(191, 219, 254),
                PortalColor.rgb(56, 189, 248),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(96, 165, 250),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(232, 241, 255),
                PortalColor.rgb(191, 219, 254),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(96, 165, 250),
                PortalColor.rgb(232, 241, 255),
                PortalColor.rgb(250, 204, 21),
                PortalColor.rgb(134, 239, 172),
                PortalColor.rgb(44, 69, 99),
                PortalColor.rgb(125, 211, 252),
                PortalColor.rgb(147, 197, 253),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(44, 69, 99),
                PortalColor.rgb(80, 100, 130),
                PortalColor.rgb(200, 220, 245)
        ));
        themes.add(new PortalTheme(
                "forest",
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(34, 197, 94),
                PortalColor.rgb(236, 253, 245),
                PortalColor.rgb(134, 164, 148),
                PortalColor.rgb(110, 231, 183),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(250, 204, 21),
                PortalColor.rgb(248, 113, 113),
                PortalColor.rgb(54, 94, 75),
                PortalColor.rgb(187, 247, 208),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(110, 231, 183),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(134, 164, 148),
                PortalColor.rgb(134, 164, 148),
                PortalColor.rgb(110, 231, 183),
                PortalColor.rgb(236, 253, 245),
                PortalColor.rgb(209, 250, 229),
                PortalColor.rgb(134, 164, 148),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(236, 253, 245),
                PortalColor.rgb(250, 204, 21),
                PortalColor.rgb(187, 247, 208),
                PortalColor.rgb(54, 94, 75),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(110, 231, 183),
                PortalColor.rgb(134, 164, 148),
                PortalColor.rgb(54, 94, 75),
                PortalColor.rgb(80, 110, 90),
                PortalColor.rgb(210, 240, 220)
        ));
        themes.add(new PortalTheme(
                "graphite",
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(226, 232, 240),
                PortalColor.rgb(248, 250, 252),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(196, 181, 253),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(251, 191, 36),
                PortalColor.rgb(248, 113, 113),
                PortalColor.rgb(71, 85, 105),
                PortalColor.rgb(226, 232, 240),
                PortalColor.rgb(226, 232, 240),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(226, 232, 240),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(241, 245, 249),
                PortalColor.rgb(248, 250, 252),
                PortalColor.rgb(203, 213, 225),
                PortalColor.rgb(226, 232, 240),
                PortalColor.rgb(248, 250, 252),
                PortalColor.rgb(250, 204, 21),
                PortalColor.rgb(196, 181, 253),
                PortalColor.rgb(71, 85, 105),
                PortalColor.rgb(226, 232, 240),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(148, 163, 184),
                PortalColor.rgb(71, 85, 105),
                PortalColor.rgb(100, 116, 139),
                PortalColor.rgb(200, 210, 225)
        ));
        themes.add(new PortalTheme(
                "sakura",
                PortalColor.rgb(244, 114, 182),
                PortalColor.rgb(236, 72, 153),
                PortalColor.rgb(255, 241, 242),
                PortalColor.rgb(190, 152, 165),
                PortalColor.rgb(236, 161, 190),
                PortalColor.rgb(74, 222, 128),
                PortalColor.rgb(251, 191, 36),
                PortalColor.rgb(248, 113, 113),
                PortalColor.rgb(133, 78, 107),
                PortalColor.rgb(251, 207, 232),
                PortalColor.rgb(244, 114, 182),
                PortalColor.rgb(236, 161, 190),
                PortalColor.rgb(244, 114, 182),
                PortalColor.rgb(190, 152, 165),
                PortalColor.rgb(190, 152, 165),
                PortalColor.rgb(236, 161, 190),
                PortalColor.rgb(252, 231, 243),
                PortalColor.rgb(255, 241, 242),
                PortalColor.rgb(251, 207, 232),
                PortalColor.rgb(244, 114, 182),
                PortalColor.rgb(255, 241, 242),
                PortalColor.rgb(251, 191, 36),
                PortalColor.rgb(253, 186, 116),
                PortalColor.rgb(133, 78, 107),
                PortalColor.rgb(244, 114, 182),
                PortalColor.rgb(236, 161, 190),
                PortalColor.rgb(190, 152, 165),
                PortalColor.rgb(133, 78, 107),
                PortalColor.rgb(160, 110, 135),
                PortalColor.rgb(245, 220, 230)
        ));

        BUILT_INS = Collections.unmodifiableList(themes);
        BUILT_IN_INDEX = buildIndex(BUILT_INS);
        DEFAULT_THEME = BUILT_INS.get(0);
    }

    private PortalThemes() {
    }

    public static synchronized void replaceCustomThemes(Map<String, Map<String, String>> specs) {
        if (specs == null || specs.isEmpty()) {
            customThemes = Collections.emptyList();
            customThemeIndex = Collections.emptyMap();
            return;
        }

        try {
            List<PortalTheme> resolvedThemes = resolveCustomThemes(specs);
            customThemes = Collections.unmodifiableList(resolvedThemes);
            customThemeIndex = buildIndex(customThemes);
        } catch (Throwable e) {
            LOG.warn("Load custom portal themes failure", e);
            customThemes = Collections.emptyList();
            customThemeIndex = Collections.emptyMap();
        }
    }

    public static PortalTheme defaultTheme() {
        return DEFAULT_THEME;
    }

    public static List<PortalTheme> builtIns() {
        return BUILT_INS;
    }

    public static List<PortalTheme> allThemes() {
        LinkedHashMap<String, PortalTheme> merged = new LinkedHashMap<String, PortalTheme>();
        for (PortalTheme theme : customThemes) {
            merged.put(normalize(theme.name()), theme);
        }
        for (PortalTheme theme : BUILT_INS) {
            merged.putIfAbsent(normalize(theme.name()), theme);
        }
        return Collections.unmodifiableList(new ArrayList<PortalTheme>(merged.values()));
    }

    public static List<String> names() {
        List<String> names = new ArrayList<String>();
        for (PortalTheme theme : allThemes()) {
            names.add(theme.name());
        }
        return Collections.unmodifiableList(names);
    }

    public static List<String> supportedKeys() {
        return SUPPORTED_KEYS;
    }

    public static PortalTheme find(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }

        String normalized = normalize(name);
        PortalTheme custom = customThemeIndex.get(normalized);
        if (custom != null) {
            return custom;
        }

        return BUILT_IN_INDEX.get(normalized);
    }

    public static boolean isBuiltIn(String name) {
        return BUILT_IN_INDEX.containsKey(normalize(name));
    }

    public static boolean isCustom(String name) {
        return customThemeIndex.containsKey(normalize(name));
    }

    private static List<PortalTheme> resolveCustomThemes(Map<String, Map<String, String>> specs) {
        LinkedHashMap<String, Map<String, String>> normalizedSpecs = new LinkedHashMap<String, Map<String, String>>();
        for (Map.Entry<String, Map<String, String>> entry : specs.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                continue;
            }
            normalizedSpecs.put(entry.getKey().trim(), normalizeSpec(entry.getValue()));
        }

        LinkedHashMap<String, PortalTheme> resolved = new LinkedHashMap<String, PortalTheme>();
        for (String themeName : normalizedSpecs.keySet()) {
            resolveTheme(themeName, normalizedSpecs, resolved, new LinkedHashSet<String>());
        }

        return new ArrayList<PortalTheme>(resolved.values());
    }

    private static PortalTheme resolveTheme(String themeName,
                                            Map<String, Map<String, String>> specs,
                                            Map<String, PortalTheme> resolved,
                                            Set<String> resolving) {
        String normalizedName = normalize(themeName);
        if (resolved.containsKey(normalizedName)) {
            return resolved.get(normalizedName);
        }
        if (!resolving.add(normalizedName)) {
            throw new IllegalArgumentException("Portal theme inheritance cycle: " + themeName);
        }

        Map<String, String> spec = specs.get(themeName);
        if (spec == null) {
            throw new IllegalArgumentException("Portal theme spec not found: " + themeName);
        }

        String baseName = valueOf(spec, "extends", "base", "parent");
        PortalTheme baseTheme;
        if (isBlank(baseName)) {
            baseTheme = DEFAULT_THEME;
        } else {
            String matchedCustomName = matchCustomName(baseName, specs.keySet());
            if (matchedCustomName != null) {
                baseTheme = resolveTheme(matchedCustomName, specs, resolved, resolving);
            } else {
                baseTheme = find(baseName);
            }
        }

        if (baseTheme == null) {
            throw new IllegalArgumentException("Unknown base portal theme: " + baseName);
        }

        PortalTheme merged = mergeTheme(themeName, baseTheme, spec);
        resolving.remove(normalizedName);
        resolved.put(normalizedName, merged);
        return merged;
    }

    private static String matchCustomName(String themeName, Set<String> customNames) {
        String normalized = normalize(themeName);
        for (String item : customNames) {
            if (normalize(item).equals(normalized)) {
                return item;
            }
        }
        return null;
    }

    private static PortalTheme mergeTheme(String name, PortalTheme base, Map<String, String> spec) {
        return new PortalTheme(
                name,
                parseColor(spec, base.accent(), "accent"),
                parseColor(spec, base.accentStrong(), "accentStrong"),
                parseColor(spec, base.textPrimary(), "textPrimary"),
                parseColor(spec, base.textMuted(), "textMuted"),
                parseColor(spec, base.textSoft(), "textSoft"),
                parseColor(spec, base.success(), "success"),
                parseColor(spec, base.warning(), "warning"),
                parseColor(spec, base.error(), "error"),
                parseColor(spec, base.separator(), "separator"),
                parseColor(spec, base.userTitle(), "userTitle"),
                parseColor(spec, base.assistantTitle(), "assistantTitle"),
                parseColor(spec, base.thinkingTitle(), "thinkingTitle"),
                parseColor(spec, base.toolTitle(), "toolTitle"),
                parseColor(spec, base.blockTime(), "blockTime"),
                parseColor(spec, base.thinkingBorder(), "thinkingBorder"),
                parseColor(spec, base.toolMeta(), "toolMeta"),
                parseColor(spec, base.toolValue(), "toolValue"),
                parseColor(spec, base.toolResult(), "toolResult"),
                parseColor(spec, base.toolPreview(), "toolPreview"),
                parseColor(spec, base.markdownHeader(), "markdownHeader"),
                parseColor(spec, base.markdownBold(), "markdownBold"),
                parseColor(spec, base.markdownInlineCode(), "markdownInlineCode"),
                parseColor(spec, base.markdownCodeText(), "markdownCodeText"),
                parseColor(spec, base.markdownCodeBorder(), "markdownCodeBorder"),
                parseColor(spec, base.markdownListBullet(), "markdownListBullet"),
                parseColor(spec, base.markdownListNumber(), "markdownListNumber"),
                parseColor(spec, base.markdownBlockquote(), "markdownBlockquote"),
                parseColor(spec, base.markdownRule(), "markdownRule"),
                parseColor(spec, base.tableBorder(), "tableBorder"),
                parseColor(spec, base.tableHeader(), "tableHeader")
        );
    }

    private static PortalColor parseColor(Map<String, String> spec, PortalColor fallback, String key) {
        String value = valueOf(spec, key);
        if (isBlank(value)) {
            return fallback;
        }

        String normalized = value.trim();
        try {
            if (normalized.startsWith("#")) {
                normalized = normalized.substring(1);
            } else if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
                normalized = normalized.substring(2);
            }

            if (normalized.startsWith("rgb(") && normalized.endsWith(")")) {
                normalized = normalized.substring(4, normalized.length() - 1);
            }

            if (normalized.indexOf(',') >= 0) {
                String[] parts = normalized.split("\\s*,\\s*");
                if (parts.length == 3) {
                    return PortalColor.rgb(clamp(Integer.parseInt(parts[0])),
                            clamp(Integer.parseInt(parts[1])),
                            clamp(Integer.parseInt(parts[2])));
                }
            }

            if (normalized.length() == 3) {
                StringBuilder expanded = new StringBuilder(6);
                for (char ch : normalized.toCharArray()) {
                    expanded.append(ch).append(ch);
                }
                normalized = expanded.toString();
            }

            if (normalized.length() == 6) {
                int rgb = Integer.parseInt(normalized, 16);
                return PortalColor.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            }
        } catch (Throwable e) {
            LOG.warn("Ignore invalid portal theme color {}={}", key, value);
        }

        return fallback;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static Map<String, String> normalizeSpec(Map<String, String> spec) {
        if (spec == null || spec.isEmpty()) {
            return Collections.emptyMap();
        }

        LinkedHashMap<String, String> normalized = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : spec.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            normalized.put(normalizeKey(entry.getKey()), entry.getValue().trim());
        }
        return normalized;
    }

    private static String valueOf(Map<String, String> spec, String... keys) {
        if (spec == null || spec.isEmpty() || keys == null) {
            return null;
        }

        for (String key : keys) {
            String value = spec.get(normalizeKey(key));
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, PortalTheme> buildIndex(List<PortalTheme> themes) {
        LinkedHashMap<String, PortalTheme> index = new LinkedHashMap<String, PortalTheme>();
        for (PortalTheme theme : themes) {
            index.put(normalize(theme.name()), theme);
        }
        return Collections.unmodifiableMap(index);
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                builder.append(Character.toLowerCase(ch));
            }
        }
        return builder.toString();
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
