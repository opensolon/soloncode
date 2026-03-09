package org.noear.solon.bot.core;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

public class BrowserSkill extends AbsSkill {

    // 核心 JS 标记脚本：为视觉对齐提供编号
    private static final String MARK_JS =
            "() => {" +
                    "  const items = document.querySelectorAll('button, a, input, textarea, select, [role=\"button\"]');" +
                    "  items.forEach((el, i) => {" +
                    "    el.setAttribute('data-solon-id', i);" +
                    "    const rect = el.getBoundingClientRect();" +
                    "    if (rect.width > 0 && rect.height > 0) {" +
                    "      const marker = document.createElement('div');" +
                    "      marker.className = 'solon-marker';" +
                    "      marker.textContent = i;" +
                    "      marker.style = `position:absolute; background:red; color:white; font-size:10px; font-weight:bold; " +
                    "                     padding:2px; border-radius:2px; z-index:2147483647; pointer-events:none; " +
                    "                     top:${window.scrollY + rect.top}px; left:${window.scrollX + rect.left}px;`;" +
                    "      document.body.appendChild(marker);" +
                    "    }" +
                    "  });" +
                    "}";

    private static final String CLEAN_JS =
            "() => {" +
                    "  document.querySelectorAll('.solon-marker').forEach(e => e.remove());" +
                    "}";

    @Override
    public String description() {
        return "高级浏览器技能：支持多标签切换、编号精准定位、文件上传与下载。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String __cwd = prompt.attrAs(AgentKernel.ATTR_CWD);
        BrowserManager browserManager = BrowserManager.of(__cwd);

        StringBuilder sb = new StringBuilder();
        sb.append("## Browser 指令集\n");
        sb.append("- **视觉对齐**: 调用 `browser_screenshot` 后根据红框数字使用 `[data-solon-id='数字']` 选择器。\n");
        sb.append("- **文件传输**: 下载的文件保存在 `" + AgentKernel.SOLONCODE_DOWNLOADS + "` 目录；上传时需提供项目内的相对路径。\n");
        sb.append("- **当前标签**: ").append(browserManager.getPageMap().keySet()).append("\n");
        return sb.toString();
    }

    // --- 标签页管理 ---
    @ToolMapping(name = "browser_tab_create", description = "创建并切换到新标签页。")
    public String createTab(@Param("tab_id") String tabId, String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        browserManager.createPage(tabId);
        return "已创建并切换到: " + tabId;
    }

    @ToolMapping(name = "browser_tab_switch", description = "切换标签页。")
    public String switchTab(@Param("tab_id") String tabId,
                            String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        browserManager.switchPage(tabId);
        return "已切换至: " + tabId;
    }

    @ToolMapping(name = "browser_save_session", description = "将当前的登录状态、Cookies 等持久化到本地。建议在登录操作完成后调用。")
    public String saveSession(String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        try {
            browserManager.saveState();
            return "浏览器会话状态已成功保存。下次启动将自动保持登录。";
        } catch (Exception e) {
            return "状态保存失败: " + e.getMessage();
        }
    }

    // --- 核心交互 ---
    @ToolMapping(name = "browser_navigate", description = "访问 URL。")
    public String navigate(@Param("url") String url,
                           String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        Page page = browserManager.getCurrentPage();
        page.navigate(url);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        return String.format("已加载: %s (标题: %s)", page.url(), page.title());
    }

    @ToolMapping(name = "browser_screenshot", description = "获取带编号的页面截图。")
    public String screenshot(String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        Page page = browserManager.getCurrentPage();

        // 1. 先执行一次清理，防止之前的标记堆积
        page.evaluate(CLEAN_JS);
        // 2. 注入新标记
        page.evaluate(MARK_JS);

        // 3. 截图
        byte[] buffer = page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG));

        // 4. 立即清理标记，恢复 DOM 原状
        page.evaluate(CLEAN_JS);

        return "data:image/png;base64," + Base64.getEncoder().encodeToString(buffer);
    }

    @ToolMapping(name = "browser_interact", description = "执行交互 (click, type, hover, scroll)。")
    public String interact(@Param("action") String action,
                           @Param("selector") String selector,
                           @Param(value = "text", required = false) String text,
                           String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        Page page = browserManager.getCurrentPage();

        // 操作前清理，确保 selector 选中的是真实元素而非 marker
        page.evaluate(CLEAN_JS);

        try {
            switch (action.toLowerCase()) {
                case "click":
                    page.click(selector);
                    break;
                case "type":
                    page.fill(selector, text);
                    page.keyboard().press("Enter");
                    break;
                case "hover":
                    page.hover(selector);
                    break;
                case "scroll":
                    page.mouse().wheel(0, 500);
                    return "已滚屏";
                default:
                    return "未知动作";
            }
            page.waitForLoadState(LoadState.NETWORKIDLE);
            return "操作成功，当前 URL: " + page.url();
        } catch (Exception e) {
            return "操作失败: " + e.getMessage();
        }
    }

    // --- 文件上传与下载 (新功能) ---

    @ToolMapping(name = "browser_download", description = "点击链接并捕获下载文件。文件将保存到下载目录。")
    public String download(@Param("selector") String selector,
                           String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        Page page = browserManager.getCurrentPage();
        try {
            Download download = page.waitForDownload(() -> page.click(selector));
            Path targetPath = browserManager.getDownloadPath().resolve(download.suggestedFilename());
            download.saveAs(targetPath);
            return "文件已下载至: " + targetPath.getFileName().toString();
        } catch (Exception e) {
            return "下载失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "browser_upload", description = "向指定的文件输入框上传本地文件。")
    public String upload(@Param("selector") String selector,
                         @Param("file_path") String filePath,
                         String __cwd) {
        BrowserManager browserManager = BrowserManager.of(__cwd);

        Page page = browserManager.getCurrentPage();
        try {
            // 解析相对工作区的路径
            Path fullPath = Paths.get(__cwd).resolve(filePath).toAbsolutePath();
            page.setInputFiles(selector, fullPath);
            return "文件已成功载入上传框: " + filePath;
        } catch (Exception e) {
            return "上传失败: " + e.getMessage();
        }
    }
}