package org.noear.solon.codecli.market.mcp;

/**
 * 鉴权取值模板的解析工具。
 *
 * <p>市场数据源给出的请求头取值有两种写法，都不是可直接使用的密钥：</p>
 * <ul>
 *     <li>模板：{@code value = "Bearer {api_key}"}，花括号是待填变量，同级
 *     {@code variables.api_key} 描述该变量的机密性；</li>
 *     <li>占位文案：{@code placeholder = "Bearer vera_live_..."}，只在调用方没有 value 时出现。</li>
 * </ul>
 *
 * <p>两者共同点是<b>带有鉴权方案前缀</b>（如 {@code Bearer }）。若只预填 {@code Authorization=}，
 * 用户把 key 直接粘上去就会得到不带方案的裸值，服务端必然 401；故这里负责把前缀抠出来交给表单。</p>
 *
 * @author noear 2026/11/17 created
 */
public final class McpAuthTemplate {
    /**
     * 认可的 HTTP 鉴权方案。统一规范为「首字母大写 + 一个空格」，
     * 与前端 {@code validateAuthHeader} 的 scheme-only 校验保持同一套词表。
     */
    private static final String[] AUTH_SCHEMES = {"Bearer", "Basic", "Digest", "Token", "ApiKey"};

    /** 提示文案的最大长度（前端提示条宽度有限，源站描述有时带整段英文说明） */
    private static final int HINT_MAX = 200;

    private McpAuthTemplate() {
    }

    /**
     * 模板中第一个 {@code {} 之前的字面量，即可以直接预填的部分。
     *
     * <p>{@code "Bearer {api_key}"} → {@code "Bearer "}（保留尾随空格，用户接着粘 key）；
     * 没有花括号、或花括号前没有内容时返回 {@code null}。</p>
     */
    public static String literalPrefix(String template) {
        if (template == null) {
            return null;
        }
        int idx = template.indexOf('{');
        if (idx <= 0) {
            return null;
        }
        String prefix = template.substring(0, idx);
        return prefix.trim().isEmpty() ? null : prefix;
    }

    /**
     * 模板中第一个 {@code {name}} 的变量名，没有则返回 {@code null}。
     */
    public static String variableName(String template) {
        if (template == null) {
            return null;
        }
        int start = template.indexOf('{');
        if (start < 0) {
            return null;
        }
        int end = template.indexOf('}', start + 1);
        if (end <= start + 1) {
            return null;
        }
        return template.substring(start + 1, end).trim();
    }

    /**
     * 以鉴权方案开头的文本返回规范化前缀（{@code "bearer xxx"} → {@code "Bearer "}），
     * 否则返回 {@code null}。仅用于取值模板与占位文案，不要把普通描述整段传进来。
     */
    public static String schemePrefix(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        for (String scheme : AUTH_SCHEMES) {
            if (trimmed.length() < scheme.length()
                    || !trimmed.regionMatches(true, 0, scheme, 0, scheme.length())) {
                continue;
            }
            // 方案后必须是结束或空白，避免 "BearerToken" 这类字段名被误判成 Bearer 方案
            if (trimmed.length() == scheme.length()) {
                return scheme + " ";
            }
            char next = trimmed.charAt(scheme.length());
            if (next == ' ' || next == '\t' || next == ':') {
                return scheme + " ";
            }
        }
        return null;
    }

    /**
     * 归一化为一行的提示文案（去掉换行与连续空白），并截断到 {@link #HINT_MAX}。
     */
    public static String hint(String text) {
        if (text == null) {
            return null;
        }
        String line = text.replaceAll("\\s+", " ").trim();
        if (line.isEmpty()) {
            return null;
        }
        return line.length() > HINT_MAX ? line.substring(0, HINT_MAX) + "…" : line;
    }
}
