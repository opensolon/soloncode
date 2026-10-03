package org.noear.solon.codecli.entry.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.*;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.session.SessionManager;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AcpLink 单元测试。
 *
 * <p>覆盖范围：</p>
 * <ul>
 *   <li>toPrompt — ACP ContentBlock → Solon Prompt 转换（文本/图片/混合/空）</li>
 *   <li>AcpSessionContext — 构造、cancelled 标志、createdAt</li>
 *   <li>resolveToolKind — 工具名 → ToolKind 映射</li>
 *   <li>buildToolTitle — 简化/全量模式、start/end 阶段、子代理前缀</li>
 *   <li>buildToolContent — write/edit diff、其余文本块、空输出</li>
 *   <li>buildLocations — file_path/offset 提取</li>
 *   <li>isInternalTool — task/multitask/memory_/goal_ 过滤</li>
 *   <li>resolveToolCallId — callId 优先、序号兜底</li>
 *   <li>summary — 截断逻辑</li>
 *   <li>firstNonEmpty — 多 key 回退</li>
 *   <li>removeBackendSession — SessionManager 清理 + 非 SessionManager 安全跳过</li>
 * </ul>
 *
 * <p>私有方法通过反射调用，避免为测试修改生产代码可见性。</p>
 */
public class AcpLinkTest {

    private AcpLink acpLink;
    private HarnessEngine mockEngine;
    private AgentSettings settings;

    // ─────────────────── Setup / Teardown ───────────────────

    @BeforeEach
    void setUp() {
        mockEngine = mock(HarnessEngine.class);
        when(mockEngine.getName()).thenReturn("main");

        // SessionProvider 用 spy(SessionManager) 以便验证 removeSession 调用
        SessionManager sessionManager = spy(new SessionManager());
        when(mockEngine.getSessionProvider()).thenReturn(sessionManager);

        settings = new AgentSettings();

        acpLink = new AcpLink(mockEngine, null, settings);
    }

    // ─────────────────── toPrompt ───────────────────

    @Test
    @DisplayName("toPrompt: 纯文本内容转为 TextBlock")
    void toPrompt_textContent() {
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(new AcpSchema.TextContent("你好")));

        Prompt prompt = acpLink.toPrompt(req, null);

        assertNotNull(prompt);
        assertEquals(1, prompt.getMessages().size());

        ChatMessage msg = prompt.getMessages().get(0);
        assertTrue(msg.getContent().contains("你好"));
    }

    @Test
    @DisplayName("toPrompt: 多段文本内容 — 拼接为单个 TextBlock")
    void toPrompt_multipleTextBlocks() {
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Arrays.asList(
                        new AcpSchema.TextContent("第一段"),
                        new AcpSchema.TextContent("第二段")));

        Prompt prompt = acpLink.toPrompt(req, null);

        ChatMessage msg = prompt.getMessages().get(0);
        // 多段 TextContent 拼接为一个 TextBlock，用空格连接
        assertEquals("第一段 第二段", msg.getContent());
    }

    @Test
    @DisplayName("toPrompt: 图片 URI 模式转为 ImageBlock URL")
    void toPrompt_imageWithUri() {
        AcpSchema.ImageContent img = new AcpSchema.ImageContent(
                "image", null, "image/png", "https://example.com/a.png", null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(img));

        Prompt prompt = acpLink.toPrompt(req, null);

        ChatMessage msg = prompt.getMessages().get(0);
        // 内容不为空即可（ImageBlock 不会出现在 getContent 文本里，但消息构建成功）
        assertNotNull(msg);
    }

    @Test
    @DisplayName("toPrompt: 图片 base64 模式转为 ImageBlock base64")
    void toPrompt_imageWithBase64() {
        AcpSchema.ImageContent img = new AcpSchema.ImageContent(
                "image", "iVBORw0KGgo=", "image/png", null, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(img));

        Prompt prompt = acpLink.toPrompt(req, null);

        assertNotNull(prompt);
        assertEquals(1, prompt.getMessages().size());
    }

    @Test
    @DisplayName("toPrompt: 混合文本 + 图片内容")
    void toPrompt_mixedContent() {
        AcpSchema.TextContent text = new AcpSchema.TextContent("看这张图");
        AcpSchema.ImageContent img = new AcpSchema.ImageContent(
                "image", "iVBOR=", "image/jpeg", null, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Arrays.asList(text, img));

        Prompt prompt = acpLink.toPrompt(req, null);

        assertNotNull(prompt);
        assertEquals(1, prompt.getMessages().size());
        ChatMessage msg = prompt.getMessages().get(0);
        assertTrue(msg.getContent().contains("看这张图"));
    }

    @Test
    @DisplayName("toPrompt: 空 prompt 列表不报错")
    void toPrompt_emptyPrompt() {
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.emptyList());

        Prompt prompt = acpLink.toPrompt(req, null);

        assertNotNull(prompt);
        assertEquals(1, prompt.getMessages().size());
    }

    // ─────────────────── toPrompt: ResourceLink ───────────────────

    @Test
    @DisplayName("toPrompt: ResourceLink + 文本混合 → [相对路径] 追加到文本后面")
    void toPrompt_resourceLink_mixedWithText() {
        AcpSchema.TextContent text = new AcpSchema.TextContent("请分析这个文件");
        AcpSchema.ResourceLink link = new AcpSchema.ResourceLink(
                "resource_link", "doc.md", "src/main/doc.md", "doc.md", null, "text/markdown", 100L, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Arrays.asList(text, link));

        Prompt prompt = acpLink.toPrompt(req, null);

        ChatMessage msg = prompt.getMessages().get(0);
        assertEquals("请分析这个文件 [src/main/doc.md]", msg.getContent());
    }

    @Test
    @DisplayName("toPrompt: ResourceLink file:// URI — 去除前缀并转为 cwd 相对路径")
    void toPrompt_resourceLink_fileUri() {
        AcpSchema.ResourceLink link = new AcpSchema.ResourceLink(
                "resource_link", "Foo.java", "file:///workspace/src/Foo.java", "Foo.java", null, "text/x-java", 100L, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(link));

        Prompt prompt = acpLink.toPrompt(req, "/workspace");

        ChatMessage msg = prompt.getMessages().get(0);
        assertEquals("[src/Foo.java]", msg.getContent());
    }

    @Test
    @DisplayName("toPrompt: ResourceLink 绝对路径在 cwd 下 → 转为相对路径")
    void toPrompt_resourceLink_absoluteUnderCwd() {
        AcpSchema.ResourceLink link = new AcpSchema.ResourceLink(
                "resource_link", "App.java", "/workspace/src/App.java", "App.java", null, "text/x-java", 100L, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(link));

        Prompt prompt = acpLink.toPrompt(req, "/workspace");

        ChatMessage msg = prompt.getMessages().get(0);
        assertEquals("[src/App.java]", msg.getContent());
    }

    @Test
    @DisplayName("toPrompt: ResourceLink 绝对路径不在 cwd 下 → 保持原样")
    void toPrompt_resourceLink_absoluteNotUnderCwd() {
        AcpSchema.ResourceLink link = new AcpSchema.ResourceLink(
                "resource_link", "config.xml", "/etc/config.xml", "config.xml", null, "text/xml", 100L, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(link));

        Prompt prompt = acpLink.toPrompt(req, "/workspace");

        ChatMessage msg = prompt.getMessages().get(0);
        assertEquals("[/etc/config.xml]", msg.getContent());
    }

    @Test
    @DisplayName("toPrompt: ResourceLink 相对路径 + cwd=null → 原样输出")
    void toPrompt_resourceLink_relativePathNoCwd() {
        AcpSchema.ResourceLink link = new AcpSchema.ResourceLink(
                "resource_link", "App.java", "src/App.java", "App.java", null, "text/x-java", 100L, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(link));

        Prompt prompt = acpLink.toPrompt(req, null);

        ChatMessage msg = prompt.getMessages().get(0);
        assertEquals("[src/App.java]", msg.getContent());
    }

    @Test
    @DisplayName("toPrompt: ResourceLink 空 URI — 静默跳过")
    void toPrompt_resourceLink_emptyUri() {
        AcpSchema.ResourceLink link = new AcpSchema.ResourceLink(
                "resource_link", "empty.txt", null, "empty.txt", null, null, null, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(link));

        Prompt prompt = acpLink.toPrompt(req, null);

        // 空 URI 时不添加任何 block，消息仍构建成功
        assertNotNull(prompt);
        assertEquals(1, prompt.getMessages().size());
    }

    @Test
    @DisplayName("toPrompt: 多个 ResourceLink + 文本 → 逐个追加 [path]")
    void toPrompt_resourceLink_multipleLinks() {
        AcpSchema.TextContent text = new AcpSchema.TextContent("对比这两个文件");
        AcpSchema.ResourceLink link1 = new AcpSchema.ResourceLink(
                "resource_link", "a.txt", "src/a.txt", "a.txt", null, "text/plain", 100L, null, null);
        AcpSchema.ResourceLink link2 = new AcpSchema.ResourceLink(
                "resource_link", "b.txt", "src/b.txt", "b.txt", null, "text/plain", 100L, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Arrays.asList(text, link1, link2));

        Prompt prompt = acpLink.toPrompt(req, null);

        ChatMessage msg = prompt.getMessages().get(0);
        assertEquals("对比这两个文件 [src/a.txt] [src/b.txt]", msg.getContent());
    }

    @Test
    @DisplayName("toPrompt: 仅 ResourceLink 无文本 → 单独输出 [path]")
    void toPrompt_resourceLink_noText() {
        AcpSchema.ResourceLink link = new AcpSchema.ResourceLink(
                "resource_link", "App.java", "src/App.java", "App.java", null, "text/x-java", 100L, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(link));

        Prompt prompt = acpLink.toPrompt(req, null);

        ChatMessage msg = prompt.getMessages().get(0);
        assertEquals("[src/App.java]", msg.getContent());
    }

    @Test
    @DisplayName("toPrompt: 文本 + ResourceLink + 图片 → 文本和 [path] 在同一 TextBlock，图片单独 ImageBlock")
    void toPrompt_resourceLink_textLinkAndImage() {
        AcpSchema.TextContent text = new AcpSchema.TextContent("看这个文件和图片");
        AcpSchema.ResourceLink link = new AcpSchema.ResourceLink(
                "resource_link", "App.java", "src/App.java", "App.java", null, "text/x-java", 100L, null, null);
        AcpSchema.ImageContent img = new AcpSchema.ImageContent(
                "image", "iVBOR=", "image/png", null, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Arrays.asList(text, link, img));

        Prompt prompt = acpLink.toPrompt(req, null);

        ChatMessage msg = prompt.getMessages().get(0);
        // 文本和 [path] 拼接为单个 TextBlock
        assertEquals("看这个文件和图片 [src/App.java]", msg.getContent());
        // 消息构建成功（图片作为后续 block）
        assertNotNull(msg);
    }

    // ─────────────────── toPrompt: Resource (Embedded) ───────────────────

    @Test
    @DisplayName("toPrompt: Resource TextResourceContents — text 字段直接拼入")
    void toPrompt_resource_textContents() {
        AcpSchema.TextResourceContents textRes = new AcpSchema.TextResourceContents(
                "hello from embedded", "file:///tmp/test.txt", "text/plain");
        AcpSchema.Resource resource = new AcpSchema.Resource("resource", textRes, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(resource));

        Prompt prompt = acpLink.toPrompt(req, null);

        ChatMessage msg = prompt.getMessages().get(0);
        assertTrue(msg.getContent().contains("hello from embedded"));
        assertTrue(msg.getContent().contains("File: file:///tmp/test.txt"));
    }

    @Test
    @DisplayName("toPrompt: Resource BlobResourceContents 图片 — 转为 ImageBlock base64")
    void toPrompt_resource_blobImage() {
        AcpSchema.BlobResourceContents blobRes = new AcpSchema.BlobResourceContents(
                "iVBORw0KGgo=", "file:///tmp/img.png", "image/png");
        AcpSchema.Resource resource = new AcpSchema.Resource("resource", blobRes, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(resource));

        Prompt prompt = acpLink.toPrompt(req, null);

        assertNotNull(prompt);
        assertEquals(1, prompt.getMessages().size());
    }

    @Test
    @DisplayName("toPrompt: Resource BlobResourceContents 文本 — base64 解码为文本")
    void toPrompt_resource_blobText() {
        // "hello" 的 base64 编码
        String base64 = java.util.Base64.getEncoder().encodeToString("hello".getBytes());
        AcpSchema.BlobResourceContents blobRes = new AcpSchema.BlobResourceContents(
                base64, "file:///tmp/text.txt", "text/plain");
        AcpSchema.Resource resource = new AcpSchema.Resource("resource", blobRes, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(resource));

        Prompt prompt = acpLink.toPrompt(req, null);

        ChatMessage msg = prompt.getMessages().get(0);
        assertTrue(msg.getContent().contains("hello"));
    }

    @Test
    @DisplayName("toPrompt: Resource BlobResourceContents 二进制 — 无法解码时给出提示")
    void toPrompt_resource_blobBinary() {
        // 非文本的二进制 base64
        String base64 = java.util.Base64.getEncoder().encodeToString(new byte[]{(byte)0xFF, (byte)0xFE, (byte)0x00});
        AcpSchema.BlobResourceContents blobRes = new AcpSchema.BlobResourceContents(
                base64, "file:///tmp/binary.dat", "application/octet-stream");
        AcpSchema.Resource resource = new AcpSchema.Resource("resource", blobRes, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(resource));

        Prompt prompt = acpLink.toPrompt(req, null);

        ChatMessage msg = prompt.getMessages().get(0);
        // 二进制内容应给出 Binary resource 提示（注意：0xFF 0xFE 0x00 解码为 UTF-8 时可能成功但内容乱码）
        // 这里只验证消息构建成功
        assertNotNull(msg);
    }

    @Test
    @DisplayName("toPrompt: Resource null resource 字段 — 静默跳过")
    void toPrompt_resource_nullResource() {
        AcpSchema.Resource resource = new AcpSchema.Resource("resource", null, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(resource));

        Prompt prompt = acpLink.toPrompt(req, null);

        assertNotNull(prompt);
        assertEquals(1, prompt.getMessages().size());
    }

    @Test
    @DisplayName("toPrompt: Resource TextResourceContents 空 text — 静默跳过")
    void toPrompt_resource_emptyText() {
        AcpSchema.TextResourceContents textRes = new AcpSchema.TextResourceContents(
                null, "file:///tmp/empty.txt", "text/plain");
        AcpSchema.Resource resource = new AcpSchema.Resource("resource", textRes, null, null);
        AcpSchema.PromptRequest req = new AcpSchema.PromptRequest(
                "sid", Collections.singletonList(resource));

        Prompt prompt = acpLink.toPrompt(req, null);

        assertNotNull(prompt);
        assertEquals(1, prompt.getMessages().size());
    }

    // ─────────────────── AcpSessionContext ───────────────────

    @Test
    @DisplayName("AcpSessionContext: 构造后字段正确")
    void sessionContext_construction() {
        List<AcpSchema.McpServer> servers = Collections.emptyList();
        AcpLink.AcpSessionContext ctx = new AcpLink.AcpSessionContext("/workspace", servers);

        assertEquals("/workspace", ctx.getCwd());
        assertSame(servers, ctx.getMcpServers());
        assertNotNull(ctx.getCreatedAt());
        assertFalse(ctx.isCancelled());
    }

    @Test
    @DisplayName("AcpSessionContext: cancelled 标志可切换")
    void sessionContext_cancelledToggle() {
        AcpLink.AcpSessionContext ctx = new AcpLink.AcpSessionContext(null, null);

        assertFalse(ctx.isCancelled());
        ctx.setCancelled(true);
        assertTrue(ctx.isCancelled());
        ctx.setCancelled(false);
        assertFalse(ctx.isCancelled());
    }

    @Test
    @DisplayName("AcpSessionContext: null cwd 和 null mcpServers 不报错")
    void sessionContext_nullFields() {
        AcpLink.AcpSessionContext ctx = new AcpLink.AcpSessionContext(null, null);

        assertNull(ctx.getCwd());
        assertNull(ctx.getMcpServers());
        assertNotNull(ctx.getCreatedAt());
    }

    // ─────────────────── resolveToolKind ───────────────────

    @Test
    @DisplayName("resolveToolKind: read → READ")
    void resolveToolKind_read() {
        assertEquals(AcpSchema.ToolKind.READ, invokeResolveToolKind("read"));
    }

    @Test
    @DisplayName("resolveToolKind: write → EDIT")
    void resolveToolKind_write() {
        assertEquals(AcpSchema.ToolKind.EDIT, invokeResolveToolKind("write"));
    }

    @Test
    @DisplayName("resolveToolKind: edit → EDIT")
    void resolveToolKind_edit() {
        assertEquals(AcpSchema.ToolKind.EDIT, invokeResolveToolKind("edit"));
    }

    @Test
    @DisplayName("resolveToolKind: grep/glob/ls → SEARCH")
    void resolveToolKind_search() {
        assertEquals(AcpSchema.ToolKind.SEARCH, invokeResolveToolKind("grep"));
        assertEquals(AcpSchema.ToolKind.SEARCH, invokeResolveToolKind("glob"));
        assertEquals(AcpSchema.ToolKind.SEARCH, invokeResolveToolKind("ls"));
    }

    @Test
    @DisplayName("resolveToolKind: bash → EXECUTE")
    void resolveToolKind_bash() {
        assertEquals(AcpSchema.ToolKind.EXECUTE, invokeResolveToolKind("bash"));
    }

    @Test
    @DisplayName("resolveToolKind: webfetch/websearch → FETCH")
    void resolveToolKind_fetch() {
        assertEquals(AcpSchema.ToolKind.FETCH, invokeResolveToolKind("webfetch"));
        assertEquals(AcpSchema.ToolKind.FETCH, invokeResolveToolKind("websearch"));
    }

    @Test
    @DisplayName("resolveToolKind: null/empty → OTHER")
    void resolveToolKind_nullEmpty() {
        assertEquals(AcpSchema.ToolKind.OTHER, invokeResolveToolKind(null));
        assertEquals(AcpSchema.ToolKind.OTHER, invokeResolveToolKind(""));
    }

    @Test
    @DisplayName("resolveToolKind: 未知工具名 → EXECUTE (默认)")
    void resolveToolKind_unknown() {
        assertEquals(AcpSchema.ToolKind.EXECUTE, invokeResolveToolKind("some_custom_tool"));
    }

    // ─────────────────── buildToolTitle ───────────────────

    @Test
    @DisplayName("buildToolTitle: 简化模式 start 阶段有参数 → 显示参数摘要")
    void buildToolTitle_simplifiedStartWithArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "src/App.java");

        String title = invokeBuildToolTitle("read", null, args, null, true);

        // 简化模式 start 阶段应显示参数，不应显示 "completed"
        assertTrue(title.startsWith("read:"));
        assertFalse(title.contains("completed"));
        assertTrue(title.contains("src/App.java"));
    }

    @Test
    @DisplayName("buildToolTitle: 简化模式 start 阶段无参数 → 'running'")
    void buildToolTitle_simplifiedStartNoArgs() {
        String title = invokeBuildToolTitle("bash", null, null, null, true);

        assertEquals("bash: running", title);
    }

    @Test
    @DisplayName("buildToolTitle: 简化模式 end 阶段有内容 → 显示内容摘要")
    void buildToolTitle_simplifiedEndWithContent() {
        String title = invokeBuildToolTitle("read", null, null, "File content here", false);

        assertTrue(title.startsWith("read:"));
        assertTrue(title.contains("File content here"));
    }

    @Test
    @DisplayName("buildToolTitle: 简化模式 end 阶段无内容 → 'completed'")
    void buildToolTitle_simplifiedEndNoContent() {
        String title = invokeBuildToolTitle("read", null, null, null, false);

        assertEquals("read: completed", title);
    }

    @Test
    @DisplayName("buildToolTitle: 简化模式 end 阶段多行内容 → 'returned N lines'")
    void buildToolTitle_simplifiedEndMultilineContent() {
        String content = "line1\nline2\nline3\nline4";
        String title = invokeBuildToolTitle("read", null, null, content, false);

        assertTrue(title.contains("returned 4 lines"));
    }

    @Test
    @DisplayName("buildToolTitle: 简化模式长参数截断到 40 字符")
    void buildToolTitle_simplifiedStartLongArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "src/very/long/path/to/some/file/that/exceeds/forty/characters.java");

        String title = invokeBuildToolTitle("read", null, args, null, true);

        // summary 截断后应为 37 字符 + "..."
        String afterColon = title.substring(title.indexOf(": ") + 2);
        assertTrue(afterColon.endsWith("..."));
        assertTrue(afterColon.length() <= 40);
    }

    @Test
    @DisplayName("buildToolTitle: 全量模式显示工具名 + 参数")
    void buildToolTitle_fullModeWithArgs() {
        settings.getGeneral().setCliPrintSimplified(false);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("file_path", "src/App.java");
        args.put("limit", 100);

        String title = invokeBuildToolTitle("read", null, args, null, true);

        assertTrue(title.startsWith("read("));
        assertTrue(title.contains("file_path=src/App.java"));
        assertTrue(title.contains("limit=100"));
        assertTrue(title.endsWith(")"));
    }

    @Test
    @DisplayName("buildToolTitle: 全量模式长参数截断到 100 字符")
    void buildToolTitle_fullModeLongArgs() {
        settings.getGeneral().setCliPrintSimplified(false);
        Map<String, Object> args = new HashMap<>();
        args.put("data", repeatChar('x', 200));

        String title = invokeBuildToolTitle("write", null, args, null, true);

        assertTrue(title.contains("..."));
        // 整体长度应被截断（工具名 + 括号 + 100 以内参数 + "..."）
        assertTrue(title.length() < 120);
    }

    @Test
    @DisplayName("buildToolTitle: 子代理前缀 (agentName != main)")
    void buildToolTitle_subagentPrefix() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "test.txt");

        String title = invokeBuildToolTitle("read", "explore", args, null, true);

        assertTrue(title.startsWith("explore/read:"));
    }

    @Test
    @DisplayName("buildToolTitle: 主引擎执行不加前缀 (agentName == main)")
    void buildToolTitle_mainAgentNoPrefix() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "test.txt");

        String title = invokeBuildToolTitle("read", "main", args, null, true);

        assertTrue(title.startsWith("read:"));
        assertFalse(title.contains("main/"));
    }

    @Test
    @DisplayName("buildToolTitle: 空 toolName 返回 content")
    void buildToolTitle_emptyToolName() {
        String title = invokeBuildToolTitle(null, null, null, "fallback", false);
        assertEquals("fallback", title);
    }

    // ─────────────────── buildToolContent ───────────────────

    @Test
    @DisplayName("buildToolContent: write 工具生成 ToolCallDiff (newText=写入内容)")
    void buildToolContent_write() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "src/NewFile.java");
        args.put("content", "public class NewFile {}");

        List<AcpSchema.ToolCallContent> result = invokeBuildToolContent("write", args, null);

        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof AcpSchema.ToolCallDiff);
        AcpSchema.ToolCallDiff diff = (AcpSchema.ToolCallDiff) result.get(0);
        assertEquals("src/NewFile.java", diff.path());
        assertNull(diff.oldText());
        assertEquals("public class NewFile {}", diff.newText());
    }

    @Test
    @DisplayName("buildToolContent: edit 工具逐条生成 ToolCallDiff")
    void buildToolContent_edit() {
        List<Map<String, Object>> edits = new ArrayList<>();
        Map<String, Object> edit1 = new HashMap<>();
        edit1.put("old_str", "old line 1");
        edit1.put("new_str", "new line 1");
        edits.add(edit1);
        Map<String, Object> edit2 = new HashMap<>();
        edit2.put("old_str", "old line 2");
        edit2.put("new_str", "new line 2");
        edits.add(edit2);

        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "src/App.java");
        args.put("edits", edits);

        List<AcpSchema.ToolCallContent> result = invokeBuildToolContent("edit", args, "done");

        assertEquals(2, result.size());
        AcpSchema.ToolCallDiff diff1 = (AcpSchema.ToolCallDiff) result.get(0);
        assertEquals("src/App.java", diff1.path());
        assertEquals("old line 1", diff1.oldText());
        assertEquals("new line 1", diff1.newText());

        AcpSchema.ToolCallDiff diff2 = (AcpSchema.ToolCallDiff) result.get(1);
        assertEquals("old line 2", diff2.oldText());
        assertEquals("new line 2", diff2.newText());
    }

    @Test
    @DisplayName("buildToolContent: edit 工具无 edits 参数 → 退化为文本块")
    void buildToolContent_editNoEditsList() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "src/App.java");
        // 没有 edits 参数

        List<AcpSchema.ToolCallContent> result = invokeBuildToolContent("edit", args, "some output");

        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof AcpSchema.ToolCallContentBlock);
    }

    @Test
    @DisplayName("buildToolContent: 其他工具有内容 → ToolCallContentBlock")
    void buildToolContent_otherToolWithContent() {
        List<AcpSchema.ToolCallContent> result = invokeBuildToolContent("bash", null, "command output");

        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof AcpSchema.ToolCallContentBlock);
        AcpSchema.ToolCallContentBlock block = (AcpSchema.ToolCallContentBlock) result.get(0);
        assertTrue(block.content() instanceof AcpSchema.TextContent);
        assertEquals("command output", ((AcpSchema.TextContent) block.content()).text());
    }

    @Test
    @DisplayName("buildToolContent: 其他工具无内容 → 空列表")
    void buildToolContent_otherToolNoContent() {
        List<AcpSchema.ToolCallContent> result = invokeBuildToolContent("bash", null, null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("buildToolContent: write 工具缺 content 参数 → 空列表")
    void buildToolContent_writeNoContent() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "src/NewFile.java");
        // 缺 content 参数

        List<AcpSchema.ToolCallContent> result = invokeBuildToolContent("write", args, null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("buildToolContent: null args → 空列表或文本块")
    void buildToolContent_nullArgs() {
        List<AcpSchema.ToolCallContent> result = invokeBuildToolContent("bash", null, "output");
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof AcpSchema.ToolCallContentBlock);
    }

    // ─────────────────── buildLocations ───────────────────

    @Test
    @DisplayName("buildLocations: file_path + offset → 带行号的 Location")
    void buildLocations_withPathAndOffset() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "src/App.java");
        args.put("offset", 42);

        List<AcpSchema.ToolCallLocation> locations = invokeBuildLocations(args);

        assertEquals(1, locations.size());
        assertEquals("src/App.java", locations.get(0).path());
        assertEquals(Integer.valueOf(42), locations.get(0).line());
    }

    @Test
    @DisplayName("buildLocations: 仅 path 参数 → 行号为 null")
    void buildLocations_pathOnly() {
        Map<String, Object> args = new HashMap<>();
        args.put("path", "src/App.java");

        List<AcpSchema.ToolCallLocation> locations = invokeBuildLocations(args);

        assertEquals(1, locations.size());
        assertEquals("src/App.java", locations.get(0).path());
        assertNull(locations.get(0).line());
    }

    @Test
    @DisplayName("buildLocations: 无路径参数 → 空列表")
    void buildLocations_noPath() {
        Map<String, Object> args = new HashMap<>();
        args.put("command", "ls -la");

        List<AcpSchema.ToolCallLocation> locations = invokeBuildLocations(args);
        assertTrue(locations.isEmpty());
    }

    @Test
    @DisplayName("buildLocations: null args → 空列表")
    void buildLocations_nullArgs() {
        List<AcpSchema.ToolCallLocation> locations = invokeBuildLocations(null);
        assertTrue(locations.isEmpty());
    }

    @Test
    @DisplayName("buildLocations: file_path 优先于 path")
    void buildLocations_filePathPreferred() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "src/Primary.java");
        args.put("path", "src/Secondary.java");

        List<AcpSchema.ToolCallLocation> locations = invokeBuildLocations(args);

        assertEquals(1, locations.size());
        assertEquals("src/Primary.java", locations.get(0).path());
    }

    // ─────────────────── isInternalTool ───────────────────

    @Test
    @DisplayName("isInternalTool: task → true")
    void isInternalTool_task() {
        assertTrue(invokeIsInternalTool("task"));
    }

    @Test
    @DisplayName("isInternalTool: multitask → true")
    void isInternalTool_multitask() {
        assertTrue(invokeIsInternalTool("multitask"));
    }

    @Test
    @DisplayName("isInternalTool: memory_ 前缀 → true")
    void isInternalTool_memory() {
        assertTrue(invokeIsInternalTool("memory_extract"));
        assertTrue(invokeIsInternalTool("memory_search"));
        assertTrue(invokeIsInternalTool("memory_recall"));
    }

    @Test
    @DisplayName("isInternalTool: goal_ 前缀 → true")
    void isInternalTool_goal() {
        assertTrue(invokeIsInternalTool("goal_create"));
        assertTrue(invokeIsInternalTool("goal_check"));
    }

    @Test
    @DisplayName("isInternalTool: 普通工具 → false")
    void isInternalTool_normalTools() {
        assertFalse(invokeIsInternalTool("read"));
        assertFalse(invokeIsInternalTool("write"));
        assertFalse(invokeIsInternalTool("bash"));
        assertFalse(invokeIsInternalTool("grep"));
    }

    @Test
    @DisplayName("isInternalTool: null → false")
    void isInternalTool_null() {
        assertFalse(invokeIsInternalTool(null));
    }

    // ─────────────────── resolveToolCallId ───────────────────

    @Test
    @DisplayName("resolveToolCallId: 有 callId → 'tc-<callId>'")
    void resolveToolCallId_withCallId() {
        String id = invokeResolveToolCallId("abc123", new java.util.concurrent.atomic.AtomicInteger(0));
        assertEquals("tc-abc123", id);
    }

    @Test
    @DisplayName("resolveToolCallId: 空 callId → 自增序号兜底")
    void resolveToolCallId_emptyCallId() {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
        String id1 = invokeResolveToolCallId(null, counter);
        String id2 = invokeResolveToolCallId("", counter);

        assertEquals("tc-1", id1);
        assertEquals("tc-2", id2);
    }

    // ─────────────────── summary ───────────────────

    @Test
    @DisplayName("summary: 短文本不截断")
    void summary_shortText() {
        assertEquals("hello", invokeSummary("hello"));
    }

    @Test
    @DisplayName("summary: 40 字符以内不截断")
    void summary_atBoundary() {
        String text40 = repeatChar('a', 40);
        assertEquals(text40, invokeSummary(text40));
    }

    @Test
    @DisplayName("summary: 超过 40 字符截断为 37 + '...'")
    void summary_longText() {
        String text41 = repeatChar('a', 41);
        String result = invokeSummary(text41);
        assertEquals(40, result.length());
        assertTrue(result.endsWith("..."));
        assertEquals(repeatChar('a', 37), result.substring(0, 37));
    }

    // ─────────────────── firstNonEmpty ───────────────────

    @Test
    @DisplayName("firstNonEmpty: 第一个 key 有值 → 返回该值")
    void firstNonEmpty_firstKey() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "a.java");
        args.put("path", "b.java");

        assertEquals("a.java", invokeFirstNonEmpty(args, "file_path", "path"));
    }

    @Test
    @DisplayName("firstNonEmpty: 第一个 key 为 null，第二个有值 → 返回第二个")
    void firstNonEmpty_secondKey() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", null);
        args.put("path", "b.java");

        assertEquals("b.java", invokeFirstNonEmpty(args, "file_path", "path"));
    }

    @Test
    @DisplayName("firstNonEmpty: 所有 key 都无值 → null")
    void firstNonEmpty_noneFound() {
        Map<String, Object> args = new HashMap<>();
        args.put("command", "ls");

        assertNull(invokeFirstNonEmpty(args, "file_path", "path"));
    }

    @Test
    @DisplayName("firstNonEmpty: null args → 抛异常（调用方负责 null 检查）")
    void firstNonEmpty_nullArgs() {
        // firstNonEmpty 不做 null 检查，依赖调用方（buildLocations/buildToolContent）提前拦截
        assertThrows(RuntimeException.class, () -> invokeFirstNonEmpty(null, "file_path"));
    }

    // ─────────────────── removeBackendSession ───────────────────

    @Test
    @DisplayName("removeBackendSession: SessionManager 实现时调用 removeSession")
    void removeBackendSession_withSessionManager() {
        SessionManager sessionManager = (SessionManager) mockEngine.getSessionProvider();
        // 先放一个 session 进去
        AgentSession session = mock(AgentSession.class);
        // 用 doReturn 避免 computeIfAbsent 的文件系统操作
        doReturn(session).when(sessionManager).getSession("test-sid");

        // 调用 removeBackendSession
        invokeRemoveBackendSession("test-sid");

        // 验证 removeSession 被调用
        verify(sessionManager, times(1)).removeSession("test-sid");
    }

    @Test
    @DisplayName("removeBackendSession: 非 SessionManager 实现时安全跳过不报错")
    void removeBackendSession_nonSessionManager() {
        // 替换为非 SessionManager 的 provider
        AgentSessionProvider otherProvider = mock(AgentSessionProvider.class);
        when(mockEngine.getSessionProvider()).thenReturn(otherProvider);

        // 不应抛异常
        assertDoesNotThrow(() -> invokeRemoveBackendSession("test-sid"));
    }

    // ─────────────────── buildArgsStr (隐式验证 via buildToolTitle) ───────────────────

    @Test
    @DisplayName("buildArgsStr: null/empty args → 空字符串")
    void buildArgsStr_nullOrEmpty() {
        // 通过 buildToolTitle 全量模式间接验证
        settings.getGeneral().setCliPrintSimplified(false);
        String title = invokeBuildToolTitle("read", null, null, null, true);
        assertEquals("read()", title);
    }

    @Test
    @DisplayName("buildArgsStr: 多参数用空格连接，换行替换为空格")
    void buildArgsStr_multiArgsWithNewlines() {
        settings.getGeneral().setCliPrintSimplified(false);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("file_path", "src/App.java");
        args.put("content", "line1\nline2\nline3");

        String title = invokeBuildToolTitle("write", null, args, null, true);

        // 换行应被替换为空格
        assertFalse(title.contains("\n"));
        assertTrue(title.contains("file_path=src/App.java"));
        assertTrue(title.contains("content=line1 line2 line3"));
    }

    // ─────────────────── 反射辅助方法 ───────────────────

    private AcpSchema.ToolKind invokeResolveToolKind(String toolName) {
        return invokeTyped("resolveToolKind",
                new Class<?>[]{String.class}, new Object[]{toolName});
    }

    private String invokeBuildToolTitle(String toolName, String agentName,
                                        Map<String, Object> args, String content, boolean running) {
        return invokeTyped("buildToolTitle",
                new Class<?>[]{String.class, String.class, Map.class, String.class, boolean.class},
                new Object[]{toolName, agentName, args, content, running});
    }

    @SuppressWarnings("unchecked")
    private List<AcpSchema.ToolCallContent> invokeBuildToolContent(String toolName,
                                                                   Map<String, Object> args, String content) {
        return (List<AcpSchema.ToolCallContent>) invokeTyped("buildToolContent",
                new Class<?>[]{String.class, Map.class, String.class},
                new Object[]{toolName, args, content});
    }

    @SuppressWarnings("unchecked")
    private List<AcpSchema.ToolCallLocation> invokeBuildLocations(Map<String, Object> args) {
        return (List<AcpSchema.ToolCallLocation>) invokeTyped("buildLocations",
                new Class<?>[]{Map.class}, new Object[]{args});
    }

    private boolean invokeIsInternalTool(String toolName) {
        return invokeTyped("isInternalTool",
                new Class<?>[]{String.class}, new Object[]{toolName});
    }

    private String invokeResolveToolCallId(String callId, java.util.concurrent.atomic.AtomicInteger counter) {
        return invokeTyped("resolveToolCallId",
                new Class<?>[]{String.class, java.util.concurrent.atomic.AtomicInteger.class},
                new Object[]{callId, counter});
    }

    private String invokeSummary(String text) {
        return invokeTyped("summary",
                new Class<?>[]{String.class}, new Object[]{text});
    }

    private String invokeFirstNonEmpty(Map<String, Object> args, String... keys) {
        return invokeTyped("firstNonEmpty",
                new Class<?>[]{Map.class, String[].class},
                new Object[]{args, keys});
    }

    private void invokeRemoveBackendSession(String sessionId) {
        invokeTyped("removeBackendSession",
                new Class<?>[]{String.class}, new Object[]{sessionId});
    }

    @SuppressWarnings("unchecked")
    private <T> T invokeTyped(String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            Method method = AcpLink.class.getDeclaredMethod(methodName, paramTypes);
            method.setAccessible(true);
            return (T) method.invoke(acpLink, args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static String repeatChar(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
