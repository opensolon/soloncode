package org.noear.solon.codecli.config;

import org.noear.solon.ai.mcp.McpChannel;

import java.util.*;

/**
 * MCP 类型解析器 —— 统一处理 MCP Server type 的标准化、校验和通道映射。
 *
 * <p>外部来源（JSON 导入、用户输入）可能使用多种别名表示同一传输协议，
 * 本工具类将所有别名收敛为三种标准内部类型：{@code stdio}、{@code sse}、{@code streamable}。</p>
 *
 * <h3>别名映射表</h3>
 * <pre>
 *   外部别名                    → 标准类型
 *   ───────────────────────────────────────────────
 *   "stdio"                    → "stdio"
 *   "local"                    → "stdio"
 *   "sse"                      → "sse"
 *   "sse_http"                 → "sse"
 *   "streamable"               → "streamable"
 *   "streamable_http"          → "streamable"
 *   "remote"                   → "streamable"
 *   "streamable_stateless"     → "streamable_stateless"
 * </pre>
 *
 * @author noear 2026/7/23
 */
public class McpTypeResolver {

    // ==================== 标准内部类型常量 ====================

    /** 标准类型：stdio（本地进程通信） */
    public static final String TYPE_STDIO = McpChannel.STDIO;

    /** 标准类型：sse（Server-Sent Events，服务端推送） */
    public static final String TYPE_SSE = McpChannel.SSE;

    /** 标准类型：streamable（可流式 HTTP） */
    public static final String TYPE_STREAMABLE = McpChannel.STREAMABLE;

    /** 标准类型：streamable_stateless（无状态流式） */
    public static final String TYPE_STREAMABLE_STATELESS = McpChannel.STREAMABLE_STATELESS;

    /** 所有标准类型的集合（用于校验） */
    private static final Set<String> STANDARD_TYPES = new HashSet<>(Arrays.asList(
            TYPE_STDIO, TYPE_SSE, TYPE_STREAMABLE, TYPE_STREAMABLE_STATELESS
    ));

    /** 外部别名 → 标准类型的映射（只读） */
    private static final Map<String, String> ALIAS_MAP = buildAliasMap();

    private static Map<String, String> buildAliasMap() {
        Map<String, String> map = new LinkedHashMap<>();
        // stdio 及别名
        map.put("stdio", TYPE_STDIO);
        map.put("local", TYPE_STDIO);
        // sse 及别名
        map.put("sse", TYPE_SSE);
        map.put("sse_http", TYPE_SSE);
        map.put("ssehttp", TYPE_SSE);
        // streamable 及别名
        map.put("streamable", TYPE_STREAMABLE);
        map.put("streamable_http", TYPE_STREAMABLE);
        map.put("streamablehttp", TYPE_STREAMABLE);
        map.put("http", TYPE_STREAMABLE);
        map.put("remote", TYPE_STREAMABLE);
        // streamable_stateless
        map.put("streamable_stateless", TYPE_STREAMABLE_STATELESS);
        return Collections.unmodifiableMap(map);
    }

    private McpTypeResolver() {
        // 工具类，禁止实例化
    }

    // ==================== 核心 API ====================

    /**
     * 标准化 MCP type：将外部别名转为标准内部类型。
     *
     * <p>如果传入 null 或空字符串，返回 {@code null}；
     * 如果无法识别，原样返回（容错，不抛异常）。</p>
     */
    public static String standardize(String rawType) {
        if (rawType == null || rawType.isEmpty()) {
            return null;
        }
        String lower = rawType.toLowerCase().trim();
        String standard = ALIAS_MAP.get(lower);
        return (standard != null) ? standard : rawType; // 无法识别则原样返回
    }

    /**
     * 校验是否为已知的标准类型（stdio / sse / streamable / streamable_stateless）。
     */
    public static boolean isValid(String type) {
        if (type == null) {
            return false;
        }
        return STANDARD_TYPES.contains(type);
    }

    /**
     * 判断是否为 stdio 类型。
     */
    public static boolean isStdio(String type) {
        return TYPE_STDIO.equals(standardize(type));
    }

    /**
     * 判断是否为 HTTP 类协议（sse 或 streamable，含 streamable_stateless）。
     */
    public static boolean isHttpType(String type) {
        String std = standardize(type);
        return TYPE_SSE.equals(std)
                || TYPE_STREAMABLE.equals(std)
                || TYPE_STREAMABLE_STATELESS.equals(std);
    }

    /**
     * 将标准化后的类型映射为 {@link McpChannel} 常量值。
     *
     * <p>注意：streamable_stateless 作为 streamable 处理。</p>
     *
     * @return McpChannel 常量值（如 {@code "stdio"}、{@code "sse"}、{@code "streamable"}），
     *         无法识别时返回 null
     */
    public static String toChannel(String type) {
        String std = standardize(type);
        if (std == null) {
            return null;
        }
        switch (std) {
            case TYPE_STDIO:
                return McpChannel.STDIO;
            case TYPE_SSE:
                return McpChannel.SSE;
            case TYPE_STREAMABLE:
            case TYPE_STREAMABLE_STATELESS:
                return McpChannel.STREAMABLE;
            default:
                return null;
        }
    }

    /**
     * 判断给定的 type 是否属于 HTTP 类并映射为对应的 channel 值。
     *
     * @return HTTP 类协议的 channel 值（"sse" 或 "streamable"），非 HTTP 返回 null
     */
    public static String toHttpChannel(String type) {
        String std = standardize(type);
        if (TYPE_SSE.equals(std)) {
            return McpChannel.SSE;
        }
        if (TYPE_STREAMABLE.equals(std) || TYPE_STREAMABLE_STATELESS.equals(std)) {
            return McpChannel.STREAMABLE;
        }
        return null;
    }

    /**
     * 判断是否为可用的 HTTP 通道类型（含 streamable_stateless）。
     */
    public static boolean isHttpChannel(String channel) {
        return McpChannel.SSE.equals(channel)
                || McpChannel.STREAMABLE.equals(channel);
    }

    // ==================== 兼容性辅助方法 ====================

    /**
     * 从原始 type 获取可展示的友好名称（用于前端 UI 展示）。
     */
    public static String displayName(String type) {
        String std = standardize(type);
        if (std == null) {
            return "unknown";
        }
        switch (std) {
            case TYPE_STDIO:
                return "stdio";
            case TYPE_SSE:
                return "SSE";
            case TYPE_STREAMABLE:
                return "Streamable HTTP";
            case TYPE_STREAMABLE_STATELESS:
                return "Streamable (Stateless)";
            default:
                return std;
        }
    }
}
