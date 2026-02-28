package org.noear.solon.ai.codecli.core.tool;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.AbsTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CodeSearchTool - 100% 对齐 OpenCode 逻辑
 */
public class CodeSearchTool extends AbsTool {
    private static CodeSearchTool instance = new CodeSearchTool();
    public static CodeSearchTool getInstance(){
        return instance;
    }

    private static final int DEFAULT_TOKENS = 5000;
    private final McpClientProvider mcpClient;

    public CodeSearchTool() {
        // 假设 ExaMcp 已经封装了基础的 MCP 客户端连接
        this.mcpClient = ExaMcp.getMcpClient();

        // 100% 对齐参数描述文案
        addParam("query", String.class, true,
                "Search query to find relevant context for APIs, Libraries, and SDKs. " +
                        "For example, 'React useState hook examples', 'Python pandas dataframe filtering', " +
                        "'Express.js middleware', 'Next js partial prerendering configuration'");

        addParam("tokensNum", Integer.class, false,
                "Number of tokens to return (1000-50000). Default is 5000 tokens. " +
                        "Adjust this value based on how much context you need - use lower values for " +
                        "focused queries and higher values for comprehensive documentation.",
                "5000");
    }

    @Override
    public String name() {
        return "codesearch";
    }

    @Override
    public String description() {
        // 100% 对齐 codesearch.txt 内容
        return "Search and get relevant context for any programming task using Exa Code API\n" +
                "- Provides the highest quality and freshest context for libraries, SDKs, and APIs\n" +
                "- Use this tool for ANY question or task related to programming\n" +
                "- Returns comprehensive code examples, documentation, and API references\n" +
                "- Optimized for finding specific programming patterns and solutions\n\n" +
                "Usage notes:\n" +
                "  - Adjustable token count (1000-50000) for focused or comprehensive results\n" +
                "  - Default 5000 tokens provides balanced context for most queries\n" +
                "  - Use lower values for specific questions, higher values for comprehensive documentation\n" +
                "  - Supports queries about frameworks, libraries, APIs, and programming concepts\n" +
                "  - Examples: 'React useState hook examples', 'Python pandas dataframe filtering', 'Express.js middleware'";
    }

    @Override
    public Object handle(Map<String, Object> args0) throws Throwable {
        String query = (String) args0.get("query");
        Object tokensNumObj = args0.get("tokensNum");

        // 1. 参数预处理 (对齐 TS 的默认值逻辑)
        Integer tokensNum = null;
        if (tokensNumObj instanceof Number) {
            tokensNum = ((Number) tokensNumObj).intValue();
        }
        int finalTokens = (tokensNum == null) ? DEFAULT_TOKENS : tokensNum;

        // 2. 模拟 ctx.ask 权限检查 (SolonAI 内部逻辑，保持静默或记录)
        // ctx.ask({ permission: "codesearch", ... })

        Map<String, Object> toolArgs = new HashMap<>();
        toolArgs.put("query", query);
        toolArgs.put("tokensNum", finalTokens);

        // 3. 执行 MCP 调用
        ToolResult result;
        try {
            // 对齐工具名: get_code_context_exa
            result = mcpClient.callTool("get_code_context_exa", toolArgs);
        } catch (Exception e) {
            // 对齐超时文案
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout")) {
                throw new RuntimeException("Code search request timed out");
            }
            throw e;
        }

        // 4. 处理异常响应 (对齐 response.ok 后的错误处理)
        if (result.isError()) {
            // 尽量提取原始错误文案以对齐 `Code search error (${status}): ${errorText}`
            String errorText = Utils.isNotEmpty(result.getContent()) ? result.getContent() : "Unknown error";
            throw new RuntimeException("Code search error: " + errorText);
        }

        // 5. 构造输出结构 (100% 对齐 TS 的 Return 结构)
        String title = "Code search: " + query;
        Map<String, Object> response = new LinkedHashMap<>();

        if (Utils.isNotEmpty(result.getContent())) {
            response.put("output", result.getContent());
            response.put("title", title);
            response.put("metadata", new HashMap<>()); // 成功时 metadata 为空
        } else {
            // 100% 对齐兜底文案
            String fallback = "No code snippets or documentation found. Please try a different query, " +
                    "be more specific about the library or programming concept, or check the spelling of framework names.";
            response.put("output", fallback);
            response.put("title", title);
            response.put("metadata", new HashMap<>());
        }

        return response;
    }
}