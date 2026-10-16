package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 文件编辑器的前端契约：查看/编辑使用单一滚动容器，语言依赖按顺序加载。
 */
public class FileEditorWebContractTest {

    @Test
    @DisplayName("CodeMirror 查看态关闭外层滚动，避免出现双滚动条")
    void codeMirrorViewHasSingleScrollOwner() throws IOException {
        String js = resourceText("/static/js/app-git.js");
        String css = resourceText("/static/css/app.css");

        assertTrue(js.contains("centerViewerContent.classList.add('file-view-cm-active')"),
                "CodeMirror 查看态必须标记专用布局");
        assertTrue(js.contains("centerViewerContent.style.overflow = 'hidden'"),
                "CodeMirror 查看态必须关闭外层滚动");
        assertTrue(js.contains("centerViewerContent.classList.remove('file-view-cm-active')"),
                "回退到 hljs 时必须清理 CodeMirror 布局标记");
        assertTrue(css.contains(".file-view-cm-active .file-view-code"),
                "CodeMirror 查看宿主必须占满 viewer 内容区");
        assertTrue(css.contains(".file-view-cm-active .file-view-host"),
                "CodeMirror 查看宿主必须占满 viewer 内容区");
    }

    @Test
    @DisplayName("CodeMirror 语言依赖按声明顺序加载")
    void modeDependenciesLoadInOrder() throws IOException {
        String js = resourceText("/static/js/app-git.js");
        int loader = js.indexOf("function loadCmMode(modeFile, cb)");
        int next = js.indexOf("function loadNext(err)", loader);
        int unordered = js.indexOf("chain.forEach", loader);

        assertTrue(loader >= 0 && next > loader, "必须存在串行 mode 加载流程");
        assertFalse(unordered >= 0, "mode 依赖不得使用无序并行加载");
    }

    private String resourceText(String path) throws IOException {
        try (InputStream in = FileEditorWebContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("resource not found: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
