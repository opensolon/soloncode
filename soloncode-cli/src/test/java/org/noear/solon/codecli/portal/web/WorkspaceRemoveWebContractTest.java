package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区面板删除链路的静态资源契约测试。
 *
 * @author noear
 */
public class WorkspaceRemoveWebContractTest {
    @Test
    void removeAction_handlesFailuresAndCurrentWorkspace() throws IOException {
        String html = resourceText("/static/web.html");

        assertTrue(html.contains("if (!r.ok || !res || res.code !== 200)"),
                "删除请求必须同时检查 HTTP 与业务状态");
        assertTrue(html.contains("if (rm === window.__wsExplicitId)"),
                "删除当前工作区必须显式处理失效 workspaceId");
        assertTrue(html.contains("window.location.replace('/')"),
                "删除当前工作区后应直接返回首页");
        assertTrue(html.contains("rmBtn.disabled = false;"),
                "删除失败后必须恢复按钮");
        assertTrue(html.contains("showToast((err && err.message) || 'remove failed', 'error')"),
                "删除失败必须给用户反馈");
        assertTrue(html.contains("function closestWithin(node, selector, stopAt)"),
                "点击 SVG 子节点时必须使用兼容的祖先匹配，不能依赖 SVGElement.closest");
        assertTrue(html.contains("var rmBtn = closestWithin(e.target, '[data-remove]', recentGrid)"),
                "删除事件必须可靠识别按钮及其 SVG 子节点");
        assertTrue(html.contains("e.preventDefault();"),
                "删除按钮点击必须阻止默认行为");
        assertTrue(html.indexOf("doRemove();\n                        layer.close(index);") > 0,
                "确认删除后必须先启动请求再关闭第三方弹层");
        assertTrue(html.contains("type=\"button\" class=\"ws-home-card-remove\""),
                "动态删除按钮必须声明 button 类型");
        assertTrue(html.contains("aria-label=\"' + esc(removeLabel) + '\""),
                "删除按钮必须提供无障碍名称");
    }

    @Test
    void removeButton_isAvailableWithoutHover() throws IOException {
        String css = resourceText("/static/css/ws-home.css");

        assertTrue(css.contains(".ws-home-card-remove:focus-visible"),
                "键盘聚焦时必须显示删除按钮");
        assertTrue(css.contains("@media (hover: none)"),
                "触摸设备上不能只依赖 hover 显示删除按钮");
    }

    @Test
    void removeConfirmation_isVisibleWhenHomeWorkspaceIsLocked() throws IOException {
        String css = resourceText("/static/css/ws-home.css");

        assertTrue(css.contains(":not(.layui-layer):not(.layui-layer-shade)"),
                "主目录锁定模式必须放行 Layui 确认框及遮罩，否则删除操作会等待不可见的确认框");
    }

    @Test
    void workspaceList_showsLoginGuideWhenUnauthenticated() throws IOException {
        String html = resourceText("/static/web.html");

        assertTrue(html.contains("if (o.status === 401 || res.code === 401)"),
                "认证已启用但未登录时列表接口返回 401，必须识别并走登录引导分支");
        assertTrue(html.contains("function showLoginRequired()"),
                "必须有展示登录引导的处理，而非误导性的空态");
        assertTrue(html.contains("id=\"wsHomeLogin\""),
                "工作区面板必须内置登录引导元素");
        assertTrue(html.contains("window.location.href = '/login'"),
                "登录引导按钮必须跳转到 /login");

        String css = resourceText("/static/css/ws-home.css");
        assertTrue(css.contains(".ws-home-login"),
                "登录引导必须有对应样式");
    }

    private String resourceText(String path) throws IOException {
        InputStream input = WorkspaceRemoveWebContractTest.class.getResourceAsStream(path);
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
