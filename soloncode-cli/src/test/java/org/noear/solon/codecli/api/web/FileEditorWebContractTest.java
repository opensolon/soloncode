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
    @DisplayName("CodeMirror 布局状态只属于文件内层，降级态恢复内部滚动")
    void codeMirrorViewHasSingleScrollOwner() throws IOException {
        String js = resourceText("/static/js/app-git.js");
        String css = resourceText("/static/css/app.css");

        assertTrue(js.contains("codeBlock.classList.add('file-view-cm-active')"));
        assertTrue(js.contains("codeBlock.classList.remove('file-view-cm-active')"));
        assertFalse(js.contains("centerViewerContent.classList"), "文件状态不得写到共享宿主");
        assertRule(css, ".file-view-code", "flex: 1", "min-height: 0", "min-width: 0", "overflow: auto", "white-space: pre");
        assertRule(css, ".file-view-code.file-view-cm-active", "position: relative", "overflow: hidden");
        assertRule(css, ".file-view-host", "position: absolute", "inset: 0", "overflow: hidden");
    }

    @Test
    @DisplayName("共享宿主只管理布局，不允许业务切换外层滚动状态")
    void sharedViewerIsLayoutOnly() throws IOException {
        String html = resourceText("/static/web.html");
        String css = resourceText("/static/css/app.css");
        assertTrue(html.contains("<div class=\"center-viewer-content\" id=\"centerViewerContent\"></div>"));
        assertRule(css, ".center-viewer-content", "display: flex", "flex-direction: column",
                "min-height: 0", "min-width: 0", "overflow: hidden", "white-space: normal");
        for (String script : new String[]{"app-git.js", "app-memory.js", "app-automation.js"}) {
            String js = resourceText("/static/js/" + script);
            assertFalse(js.contains("centerViewerContent.style.overflow"), script);
            assertFalse(js.contains("centerViewerContent.scrollTop"), script);
        }
    }

    @Test
    @DisplayName("审查正文和目录、空态、错误态均使用内部滚动层")
    void diffViewsOwnTheirScroll() throws IOException {
        String js = resourceText("/static/js/app-git.js");
        String css = resourceText("/static/css/app.css");
        String render = js.substring(js.indexOf("function renderViewerDiff(raw)"), js.indexOf("function closeCenterViewer()"));
        assertTrue(render.contains("'<div class=\"git-diff-view\">' + html + '</div>'"));
        assertTrue(render.contains("querySelector('.git-diff-view').scrollTop = 0"));
        String open = js.substring(js.indexOf("function openDiffViewer(path, status)"), js.indexOf("function lookupGitStatus(path)"));
        assertTrue(open.contains("class=\"center-viewer-scroll\""));
        assertFalse(open.contains("centerViewerContent.innerHTML = '<div style="),
                "目录、加载、空态和错误态都必须有内层滚动");
        assertRule(css, ".git-diff-view", "flex: 1", "min-height: 0", "min-width: 0", "overflow: auto", "white-space: pre");
        assertRule(css, ".center-viewer-scroll", "flex: 1", "min-height: 0", "overflow: auto", "white-space: normal");
    }

    @Test
    @DisplayName("Markdown 预览随布局自适应，记忆和自动任务保留各自列表滚动")
    void otherViewsKeepIndependentScroll() throws IOException {
        String js = resourceText("/static/js/app-git.js");
        String css = resourceText("/static/css/app.css");
        assertFalse(js.contains("iframe.style.height"), "预览高度不得由切换时的固定像素决定");
        assertRule(css, ".file-view-md-frame-wrap", "flex: 1", "min-height: 0", "overflow: hidden");
        assertRule(css, ".file-view-md-frame", "display: block", "height: 100%");
        assertRule(css, ".file-edit-wrap", "flex: 1", "min-height: 0", "overflow: hidden");
        assertRule(css, ".center-viewer-content .file-view-media", "min-height: 0", "overflow: auto");
        assertRule(css, ".center-viewer-content .memory-panel", "flex: 1", "min-height: 0");
        assertRule(css, ".automation-page", "flex: 1", "min-height: 0");
        assertRule(css, ".memory-list", "min-height: 0", "overflow-y: auto");
        assertRule(css, ".automation-list", "min-height: 0", "overflow-y: auto");
    }

    private void assertRule(String css, String selector, String... declarations) {
        int start = css.indexOf(selector + " {");
        assertTrue(start >= 0, "missing selector: " + selector);
        String rule = css.substring(start, css.indexOf('}', start));
        for (String declaration : declarations) {
            assertTrue(rule.contains(declaration + ";"), selector + " must declare " + declaration);
        }
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

    @Test
    @DisplayName("文件树右键新建：只在可写目录出现，先展开再 diff，新文件接入现有查看/编辑流程")
    void contextMenuCreatesEntriesInWritableDirectories() throws IOException {
        String js = resourceText("/static/js/app-filer.js");

        String menu = js.substring(js.indexOf("function showFilerContextMenu(e, info)"), js.indexOf("function promptCreateEntry(info, type)"));
        assertTrue(menu.contains("if (info.isDirectory && info.writable)"), "新建项只给可写目录");
        assertTrue(menu.contains("promptCreateEntry(info, 'file')"));
        assertTrue(menu.contains("promptCreateEntry(info, 'directory')"));

        String entry = js.substring(js.indexOf("function initFilerContextMenu()"), js.indexOf("function filerUrl(basePath, params)"));
        assertTrue(entry.contains("hasClass('file-workspace-readonly')"), "可写判定必须跟随工作区根节点的只读标记");

        String create = js.substring(js.indexOf("function createEntry(info, name, type)"), js.indexOf("function revealCreatedEntry(info)"));
        assertTrue(create.contains("'/web/chat/filer/create'"));
        assertTrue(create.contains("res.description || res.message"), "失败提示必须读后端 description");
        assertTrue(create.contains("window.openFileViewer(viewPath"), "新建文件后直接打开查看器");
        assertTrue(create.contains("{ mount: info.wsId || 'workspace' }"),
                "必须显式传所属工作区，否则按最近展开的根解析，会读写到别的挂载里的同名文件");

        String prompt = js.substring(js.indexOf("function promptCreateEntry(info, type)"), js.indexOf("function createEntry(info, name, type)"));
        assertTrue(prompt.contains("oe.isComposing") && prompt.contains("ev.keyCode === 229") && prompt.contains("compositionstart"),
                "回车提交必须避开输入法组合态（jQuery 事件不带 isComposing）");

        String fileClick = js.substring(js.indexOf("// 文件：单击打开查看器，双击加入对话"));
        assertTrue(fileClick.contains("window.openFileViewer(viewPath, node.name, undefined, { mount: wsId })"),
                "文件树点文件也必须显式传所属工作区");

        String reveal = js.substring(js.indexOf("function revealCreatedEntry(info)"), js.indexOf("function initFilerContextMenu()"));
        int open = reveal.indexOf("$children.addClass('open')");
        int diff = reveal.indexOf("diffDir(info.wsId, info.relPath)");
        assertTrue(open >= 0 && diff > open, "diffDir 只处理已展开目录，必须先展开再刷新");

        String[] locales = {"ar", "bn", "br", "bs", "da", "de", "en", "es", "fr", "gr", "it",
                "ja", "ko", "no", "pl", "ru", "th", "tr", "uk", "vi", "zh-CN", "zh-TW"};
        for (String locale : locales) {
            String json = resourceText("/static/i18n/" + locale + ".json");
            for (String key : new String[]{"filer.ctxNewFile", "filer.ctxNewFolder", "filer.newFileTitle",
                    "filer.newFolderTitle", "filer.invalidName", "filer.nameExists", "filer.createFailed"}) {
                assertTrue(json.contains("\"" + key + "\""), locale + " missing " + key);
            }
        }
    }

    @Test
    @DisplayName("新建文件弹窗（layer.prompt）两种主题都跟随主题变量与皮肤，输入框无 focus 外圈")
    void createPromptFollowsThemeAndSkin() throws IOException {
        String js = resourceText("/static/js/app-filer.js");
        String css = resourceText("/static/css/app.css");

        String prompt = js.substring(js.indexOf("function promptCreateEntry(info, type)"), js.indexOf("function createEntry(info, name, type)"));
        assertFalse(prompt.contains("layer.tips"), "校验错误不得用 layer.tips 的黑色气泡");
        assertTrue(prompt.contains("filer-create-error"));

        // 不带 [data-theme] 前缀：亮色主题也要生效
        assertRule(css, ".layui-layer.layui-layer-prompt", "background-color: var(--bg-primary)");
        assertRule(css, ".layui-layer-prompt .layui-layer-input", "background-color: var(--bg-input-box)",
                "color: var(--text-primary)", "border: 1px solid var(--border-input)", "outline: none");
        assertRule(css, ".layui-layer-prompt .layui-layer-input:focus-visible", "outline: none", "border-color: var(--accent)");
        assertRule(css, ".layui-layer-prompt .layui-layer-btn .layui-layer-btn0",
                "background-color: var(--accent-fill, var(--accent))");
        assertRule(css, ".layui-layer-prompt .filer-create-error", "color: var(--error-text)");
        assertFalse(css.contains("background-color: var(--accent) !important"),
                "白字实心按钮必须用 --accent-fill（contrast 暗色皮肤 --accent 为浅灰）");
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
