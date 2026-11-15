package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 模型显示名契约测试。
 *
 * <p>规则：界面只展示不带供应商前缀的 model 字段（缺失时回退 name）；
 * 选中值、去重键、状态比较一律仍用完整 name，避免同名模型串号。</p>
 */
public class ModelDisplayNameWebContractTest {
    @Test
    void sharedHelperPrefersModelFieldAndFallsBackToName() throws IOException {
        String base = resourceText("/static/js/app-base.js");

        assertTrue(base.contains("window.modelDisplayName = function (m) {"),
                "显示名必须由 app-base.js 提供共享 helper（其余脚本只调用，不各写一份）");
        assertTrue(base.contains("var display = (m.model || '').trim();"),
                "显示名必须优先取 model 字段");
        assertTrue(base.contains("return display !== '' ? display : (m.name || '').trim();"),
                "model 为空时必须回退到 name，保证自定义命名用户不受影响");
        // provider 为空的模型（直接在设置里添加）必须能从 name 里剥出前缀，否则短名撞车无法区分
        assertTrue(base.contains("window.modelProviderLabel = function (m) {"),
                "必须提供来源标签 helper（provider 缺失时回退 name 前缀）");
    }

    @Test
    void chatModelListKeepsNameAsSelectionKeyAndShowsDisplayName() throws IOException {
        String js = resourceText("/static/js/app-history.js");

        assertTrue(js.contains("data-model=\"' + escapeHtml(m.name) + '\" data-search=\""),
                "下拉项 data-model 必须保留完整 name（选中与回填的锚点）");
        assertTrue(js.contains("var cls = m.name === currentModel ? ' active' : '';"),
                "选中态比较必须用完整 name，否则不同供应商的同名模型会一起高亮");
        assertTrue(js.contains("escapeHtml(window.modelDisplayName(m))"),
                "下拉项主标题必须使用显示名");
        assertTrue(js.contains("function modelDisplayOf(modelName) {"),
                "触发器需要按 name 反查显示名");
        assertTrue(js.contains("var searchKey = (m.name + ' ' + (m.model || '') + ' ' + window.modelProviderLabel(m)).toLowerCase();"),
                "搜索键必须汇总完整名、短名与来源标签（provider 缺失时回退 name 前缀）");
        assertTrue(js.contains("$(this).attr('data-search')"),
                "搜索输入必须匹配 data-search");
    }

    @Test
    void automationAndAgentEditorsReuseTheSameDisplayRule() throws IOException {
        String automation = resourceText("/static/js/app-automation.js");
        assertTrue(automation.contains("label: window.modelDisplayName(x) || v,"),
                "自动化任务页模型项必须使用显示名");
        assertTrue(automation.contains("desc: x.description || '',"),
                "自动化任务页必须读 description（接口字段名），此前读 desc 导致描述恒为空");
        assertTrue(automation.contains("if (value) box.querySelector('.auto-select-current').setAttribute('title', value);"),
                "触发器的 title 必须保留完整模型值，供同名区分与溯源");

        String agents = resourceText("/static/js/app-settings-agents.js");
        assertTrue(agents.contains("if (!name || seen[name]) return;"),
                "智能体模型下拉的去重键必须仍是完整 name，不能改成 model");
        assertTrue(agents.contains("escapeHtml(window.modelDisplayName(item))"),
                "智能体模型下拉主标题必须使用显示名");
    }

    @Test
    void modelListEndpointExposesProviderForDisambiguation() throws IOException {
        Path src = Paths.get("src/main/java/org/noear/solon/codecli/api/web/controller/ChatWebController.java");
        assumeTrue(Files.exists(src), "仅在工作目录为模块根时校验源码");

        String controller = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        assertTrue(controller.contains("item.put(\"provider\", config.getProvider());"),
                "/web/chat/models 必须返回 provider，供短名同名时在副标题区分");
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = ModelDisplayNameWebContractTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
