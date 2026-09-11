package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件树刷新时动态挂载节点的静态资源契约测试。
 *
 * @author noear
 */
public class FilerWorkspaceRefreshWebContractTest {
    @Test
    void refreshedMount_usesWorkspaceRendererAndKeepsWorkspaceIdentity() throws IOException {
        String javascript = resourceText("/static/js/app-filer.js");

        assertTrue(javascript.contains(".attr('data-workspace-id', wsId)"),
                "挂载根节点必须保留 workspaceId，点击时才能请求正确的 mount");
        assertTrue(javascript.contains(".attr('data-type', 'directory')"),
                "挂载根节点必须声明目录类型，刷新 diff 不得把它误判为类型漂移");
        assertTrue(javascript.contains("if (node && node.id) {\n            appendWorkspaceNode(node, $tmp, indent);"),
                "刷新新增的挂载必须复用工作区根渲染器，以获得目录图标和展开事件");
        assertTrue(javascript.contains("var n2 = $.extend({}, node);"),
                "diff 新增节点时必须保留 id/readonly 等工作区元数据");
        assertTrue(javascript.contains("var n3 = $.extend({}, node);"),
                "diff 替换节点时必须保留 id/readonly 等工作区元数据");
    }

    private String resourceText(String path) throws IOException {
        InputStream input = FilerWorkspaceRefreshWebContractTest.class.getResourceAsStream(path);
        if (input == null) {
            throw new IOException("Missing test resource: " + path);
        }
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) >= 0) {
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
