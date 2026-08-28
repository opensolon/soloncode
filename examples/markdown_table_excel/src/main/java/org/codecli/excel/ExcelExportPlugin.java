package org.codecli.excel;

import org.noear.solon.Solon;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 表格导出 Excel 的扩展示例。
 *
 * <p>真正的导出能力由前端脚本 {@code excel-export.js} 提供（监听对话中的 markdown 表格，
 * 点击“导出Excel”后在浏览器端生成 .xlsx 并下载，不依赖后端）。该脚本随本扩展 jar 打包在
 * {@code META-INF/resources/plugin/markdown_table_excel/} 下。</p>
 *
 * <p>本插件在加载时：1）从自身 jar 读取该脚本内容，并向 Solon 注册一个路由
 * {@code /plugin/markdown_table_excel/excel-export.js} 直接对外提供；2）把该脚本 URL 追加到
 * 应用上下文属性 "frontendScripts"，供 Web 前端通过 {@code /web/frontend/scripts} 动态注入。
 * 核心对此无感知，新增插件无需改动 soloncode-cli。</p>
 */
public class ExcelExportPlugin implements Plugin {
    private static final String RESOURCE = "META-INF/resources/plugin/markdown_table_excel/excel-export.js";
    private static final String PATH = "/plugin/markdown_table_excel/excel-export.js";

    @Override
    public void start(AppContext context) {
        final String js = readResource(RESOURCE);
        if (js == null) {
            System.out.println("[markdown_table_excel] 警告：未找到内置前端脚本 " + RESOURCE);
            return;
        }
        // 1) 插件自己对外提供前端脚本（不依赖核心静态托管）
        Solon.app().router().add(PATH, new Handler() {
            @Override
            public void handle(Context ctx) throws Throwable {
                ctx.contentType("application/javascript; charset=utf-8");
                ctx.headerSet("Cache-Control", "no-cache");
                ctx.output(js.getBytes(StandardCharsets.UTF_8));
            }
        });
        // 2) 登记脚本 URL，供前端动态加载（系统属性，跨类加载器共享）
        String key = "soloncode.frontend.scripts";
        String existing = System.getProperty(key, "");
        if (!existing.isEmpty()) existing += ",";
        System.setProperty(key, existing + PATH);
        System.out.println("[markdown_table_excel] 扩展已加载：前端 markdown 表格“导出Excel”能力已启用");
    }

    private String readResource(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }
}
