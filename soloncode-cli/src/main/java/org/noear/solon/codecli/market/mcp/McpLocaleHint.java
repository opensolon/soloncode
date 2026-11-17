package org.noear.solon.codecli.market.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可本地化的提示 — i18n 键 + 占位符参数。
 *
 * <p>市场适配器产出的草稿最终要渲染进 Web 表单，而界面语言有二十多种。
 * 若适配器直接拼一段中文写进草稿，非中文用户就会在英文界面里看到中文。
 * 故这里只交出「键 + 参数」，文案由前端按当前语言取语言包渲染。</p>
 *
 * <p>取自源站的数据（如连接器描述、参数说明）不是本项目的文案，仍走
 * {@code description} 等纯文本字段，不进这里。</p>
 *
 * @author noear 2026/11/17 created
 */
public class McpLocaleHint {
    /** 语言包中的键，如 {@code mcp.marketNoteDoc} */
    private final String key;
    /** 占位符参数（{@code {names}} 之类），可为空 */
    private final Map<String, String> args;

    private McpLocaleHint(String key) {
        this.key = key;
        this.args = null;
    }

    private McpLocaleHint(String key, Map<String, String> args) {
        this.key = key;
        this.args = args;
    }

    /**
     * 无占位符的提示。
     */
    public static McpLocaleHint of(String key) {
        return new McpLocaleHint(key);
    }

    /**
     * 带一个占位符的提示。
     */
    public static McpLocaleHint of(String key, String argName, String argValue) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put(argName, argValue);
        return new McpLocaleHint(key, args);
    }

    /**
     * 带两个占位符的提示。
     */
    public static McpLocaleHint of(String key, String name1, String value1, String name2, String value2) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put(name1, value1);
        args.put(name2, value2);
        return new McpLocaleHint(key, args);
    }

    public String getKey() {
        return key;
    }

    public Map<String, String> getArgs() {
        return args;
    }
}
