package org.noear.solon.codecli.api.web.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.config.entity.GeneralGroupDo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「通用 - 模型默认」卡的前后端契约测试：思考模式 / 推理强度两档 + 上下文长度 / 子代理并发数两列。
 *
 * <p>背景：{@code AiConfig.concurrency} 的唯一消费方是 harness 的子代理并行调度
 * （{@code TaskTalent}，按当前会话模型取值，未设置才回退
 * {@code subagentDefaultConcurrency}）。因此「并发数」如果只做持久化而不同时热更新引擎，
 * 就是一个看不出问题的摆设 —— 本测试把这条接线钉住。</p>
 *
 * <p>同时锁定两个字段的「0 = 未设置」语义。注意「超时」一列已移除：{@code AiConfig.timeout}
 * 是有默认值（120s）的非空字段，无法表达「未设置」，做「模型默认超时」只能靠额外哨兵，
 * 收益不足以承担这份复杂度，故整列撤掉。</p>
 *
 * @author noear
 */
public class GeneralModelDefaultsContractTest {

    private static final String[] I18N_KEYS = {
            "general.modelDefaults.title",
            "general.modelDefaults.contextLength",
            "general.modelDefaults.contextLengthPlaceholder",
            "general.modelDefaults.concurrency",
            "general.modelDefaults.concurrencyPlaceholder",
            "general.modelDefaults.concurrencyInvalid",
            "general.modelDefaults.desc",
            // 迁入「模型默认」的两项（已去掉「默认」二字）
            "general.chatDefaults.thinkingMode",
            "general.chatDefaults.reasoningEffort",
            // 模型编辑表单的子代理并发数
            "llm.concurrency",
            "llm.concurrencyPlaceholder",
            "llm.concurrencyInvalid"
    };

    @Test
    @DisplayName("实体：两个字段默认 0（= 未设置），不引入第二套哨兵值")
    void entityDefaultsToZero() {
        GeneralGroupDo g = new GeneralGroupDo();

        assertTrue(g.getModelDefaultContextLength() == 0L, "默认上下文长度必须是 0（未设置）");
        assertTrue(g.getModelDefaultConcurrency() == 0, "默认并发数必须是 0（未设置，回退引擎缺省）");
    }

    @Test
    @DisplayName("页面：「模型默认」卡内含思考/推理档位与上下文/并发两列")
    void pageHasModelDefaultsCard() throws IOException {
        String html = resourceText("/static/web.html");

        assertTrue(html.contains("data-i18n=\"general.modelDefaults.title\""), "必须新增「模型默认」卡片标题");
        assertTrue(html.contains("id=\"generalModelDefaultContextLength\""), "必须有上下文长度输入框");
        assertTrue(html.contains("id=\"generalModelDefaultConcurrency\""), "必须有并发数输入框");
        assertFalse(html.contains("generalModelDefaultTimeout"),
                "「超时」一列已撤掉：AiConfig.timeout 是有默认值的非空字段，无法表达未设置");

        // 思考 / 推理档位已迁入「模型默认」卡，且必须排在「上下文长度」的上一行
        int conversationCard = html.indexOf("data-i18n=\"general.conversation.title\"");
        int modelDefaults = html.indexOf("data-i18n=\"general.modelDefaults.title\"");
        int thinkingMode = html.indexOf("id=\"generalDefaultThinkingMode\"");
        int reasoningEffort = html.indexOf("id=\"generalDefaultReasoningEffort\"");
        int contextLength = html.indexOf("id=\"generalModelDefaultContextLength\"");
        assertTrue(conversationCard > 0 && modelDefaults > 0 && thinkingMode > 0
                && reasoningEffort > 0 && contextLength > 0, "五处都必须存在");
        assertTrue(conversationCard < modelDefaults, "「模型默认」必须排在「对话策略」之后");
        assertTrue(modelDefaults < thinkingMode, "思考模式必须在「模型默认」卡内");
        assertTrue(thinkingMode < contextLength, "思考模式必须在「上下文长度」的上一行（同一卡内更靠前）");
        assertTrue(reasoningEffort < contextLength, "推理强度必须在「上下文长度」的上一行");
        assertTrue(conversationCard < thinkingMode, "思考模式不得残留在「对话策略」卡内");
        assertFalse(html.contains("data-i18n=\"general.chatDefaults.title\""),
                "「对话默认」卡已并入「模型默认」，不应再残留卡片标题");
    }

    @Test
    @DisplayName("两项都必须热更新引擎：保存与启动两条路径都要接上")
    void modelDefaultsWiredToEngine() throws IOException {
        String controller = javaSource("api/web/settings/WebSettingsController.java");

        assertTrue(controller.contains("engine.getSettings().setCompressionDefaultContextLength("),
                "保存通用设置时必须把上下文长度推给引擎压缩拦截器");
        assertTrue(controller.contains("WorkspaceManager.resolveCompressionDefaultContextLength("),
                "上下文长度 0 = 未设置时必须回退 CLI 缺省值（拦截器要求正值）");
        assertTrue(controller.contains("engine.getSettings().setSubagentDefaultConcurrency(g.getModelDefaultConcurrency())"),
                "保存通用设置时必须把并发数推给引擎，否则配置不生效");
        assertFalse(controller.contains("setModelDefaultTimeoutSeconds"),
                "默认超时已撤掉：AiConfig.timeout 恒非空，无法表达「未设置」");

        String manager = javaSource("workspace/WorkspaceManager.java");
        assertTrue(manager.contains(".compressionDefaultContextLength(resolveCompressionDefaultContextLength("),
                "启动构建引擎时必须带上上下文长度，否则重启后回落到硬编码 1m");
        assertTrue(manager.contains(".subagentDefaultConcurrency(wsSettings.getGeneral().getModelDefaultConcurrency())"),
                "启动构建引擎时必须带上并发数，否则重启后回落到引擎缺省值");
        assertFalse(manager.contains("modelDefaultTimeoutSeconds"),
                "默认超时已撤掉，引擎侧不得再有该接线");
    }

    @Test
    @DisplayName("模型默认不得回写到模型配置：新建模型的三条路径都不再套用")
    void modelDefaultsNotAppliedOnCreate() throws IOException {
        String controller = javaSource("api/web/settings/LlmSettingController.java");

        assertFalse(controller.contains("applyModelDefaults"),
                "新建模型时同步默认值的做法已取消：默认值改为引擎级兜底，回写会让「改通用默认」对已有模型失效");

        String llm = resourceText("/static/js/app-settings-llm.js");
        assertFalse(llm.contains("function applyModelDefaults"),
                "新建模型时不应再用模型默认预填表单，理由同上");
    }

    @Test
    @DisplayName("前端接线：读取回填、保存提交")
    void frontendWiring() throws IOException {
        String general = resourceText("/static/js/app-settings-general.js");
        assertTrue(general.contains("modelDefaultContextLength"), "通用设置必须回填/提交上下文长度");
        assertTrue(general.contains("modelDefaultConcurrency"), "通用设置必须回填/提交并发数");
        assertFalse(general.contains("modelDefaultTimeoutSeconds"), "超时一列已撤掉，前端不得再提交该字段");
    }

    @Test
    @DisplayName("i18n：22 个语言包都要有新增键，且不再残留「对话默认」旧文案")
    void i18nComplete() throws IOException {
        String zh = resourceText("/static/i18n/zh-CN.json");
        for (String key : I18N_KEYS) {
            assertTrue(zh.contains("\"" + key + "\""), "zh-CN 缺少键: " + key);
        }

        assertFalse(zh.contains("\"general.chatDefaults.title\""),
                "「对话默认」卡已并入「模型默认」，该键不再使用");
        // 已迁入「模型默认」卡：去掉「默认」二字（卡片标题已表达默认语义）
        assertTrue(zh.contains("\"general.chatDefaults.thinkingMode\": \"思考模式\""),
                "必须更名为「思考模式」");
        assertTrue(zh.contains("\"general.chatDefaults.reasoningEffort\": \"推理强度\""),
                "必须更名为「推理强度」");
        assertFalse(zh.contains("默认思考模式"), "不得再残留「默认思考模式」");
        assertFalse(zh.contains("默认推理强度"), "不得再残留「默认推理强度」");
        assertTrue(zh.contains("\"general.modelDefaults.concurrency\": \"子代理并发数\""),
                "「并发数」必须更名为「子代理并发数」");
        assertFalse(zh.contains("general.modelDefaults.timeout"),
                "超时一列已撤掉，22 个语言包都不得残留 timeout 键");
        // 「模型默认」描述已从「两项」改为具体两项（上下文长度 / 子代理并发数）
        assertTrue(zh.contains("上下文长度和子代理并发数"),
                "零位描述必须明写是哪两项，不能只写「两项」");

        // 共 22 个语言包，新增键必须全覆盖（i18n.js 会回退 zh-CN，但缺键会触发开发期告警）
        java.io.File dir = new java.io.File("src/main/resources/static/i18n");
        java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        assertTrue(files != null && files.length == 22, "i18n 语言包数量应为 22");
        for (java.io.File f : files) {
            String txt = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String key : I18N_KEYS) {
                assertTrue(txt.contains("\"" + key + "\""), f.getName() + " 缺少键: " + key);
            }
        }
    }

    /** 读主源码（java 文件不在 classpath，按 user.dir 相对定位） */
    private String javaSource(String relative) throws IOException {
        java.nio.file.Path p = java.nio.file.Paths.get("src/main/java/org/noear/solon/codecli", relative);
        if (!java.nio.file.Files.exists(p)) {
            throw new IOException("source not found: " + p.toAbsolutePath());
        }
        return new String(java.nio.file.Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private String resourceText(String path) throws IOException {
        try (InputStream in = GeneralModelDefaultsContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("resource not found: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
