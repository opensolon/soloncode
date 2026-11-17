package org.noear.solon.codecli.api.web.pipeline;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具卡片标题：MCP 网关 call_tool 按参数补出「服务/工具」，其余工具保持原样。
 */
class ToolViewUtilTitleTest {

    private static Map<String, Object> args(String server, Object tool) {
        Map<String, Object> args = new HashMap<>();
        if (server != null) {
            args.put("server_name", server);
        }
        if (tool != null) {
            args.put("tool_name", tool);
        }
        args.put("tool_args", new HashMap<String, Object>());
        return args;
    }

    @Test
    void callToolShowsServerAndTool() {
        assertEquals("call_tool · github/create_issue",
                ToolViewUtil.buildToolTitle("call_tool", args("github", "create_issue"), null, null));
        assertEquals("call_tool · github/create_issue",
                ToolViewUtil.buildToolTitle("call_tool", args("github", "create_issue"), null, "main"));
    }

    @Test
    void callToolWithoutServerShowsToolOnly() {
        // 旧会话或未带 server_name 时由网关补服务名，卡片只能展示工具名
        assertEquals("call_tool · create_issue",
                ToolViewUtil.buildToolTitle("call_tool", args(null, "create_issue"), null, null));
        assertEquals("call_tool · create_issue",
                ToolViewUtil.buildToolTitle("call_tool", args("  ", "create_issue"), null, null));
    }

    @Test
    void callToolWithoutToolNameKeepsPlainTitle() {
        assertEquals("call_tool", ToolViewUtil.buildToolTitle("call_tool", null, null, null));
        assertEquals("call_tool", ToolViewUtil.buildToolTitle("call_tool", args("github", null), null, null));
        assertEquals("call_tool", ToolViewUtil.buildToolTitle("call_tool", args("github", " "), null, null));
    }

    @Test
    void agentPrefixWrapsTheWholeTitle() {
        assertEquals("explore/call_tool · github/create_issue",
                ToolViewUtil.buildToolTitle("call_tool", args("github", "create_issue"), "explore", null));
        assertEquals("plan/call_tool · github/create_issue",
                ToolViewUtil.buildToolTitle("call_tool", args("github", "create_issue"), null, "plan"));
    }

    @Test
    void otherToolsIgnoreGatewayArgs() {
        // 只有 call_tool 是中转；别的工具即使恰好有同名参数也不改标题
        assertEquals("get_tool_detail",
                ToolViewUtil.buildToolTitle("get_tool_detail", args("github", "create_issue"), null, null));
        assertEquals("bash", ToolViewUtil.buildToolTitle("bash", null, null, null));
        assertEquals("explore/read", ToolViewUtil.buildToolTitle("read", null, "explore", null));
    }

    @Test
    void abnormalNamesAreFlattenedAndTruncated() {
        String longName = new String(new char[100]).replace('\0', 'x');
        String title = ToolViewUtil.buildToolTitle("call_tool", args("git\nhub", longName), null, null);

        assertTrue(title.startsWith("call_tool · git hub/"), title);
        assertTrue(title.endsWith("…"), title);
        assertEquals("call_tool · git hub/".length() + 64 + 1, title.length(), title);
    }
}
