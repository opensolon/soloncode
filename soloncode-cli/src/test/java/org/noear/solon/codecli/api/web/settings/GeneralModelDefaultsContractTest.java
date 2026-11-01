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
 * 「通用 - 模型默认」三列（上下文长度 / 并发数 / 超时）的前后端契约测试。
 *
 * <p>背景：{@code AiConfig.concurrency} 的唯一消费方是 harness 的子代理并行调度
 * （{@code TaskTalent}，按当前会话模型取值，未设置才回退
 * {@code subagentDefaultConcurrency}）。因此「并发数」如果只做持久化而不同时热更新引擎，
 * 就是一个看不出问题的摆设 —— 本测试把这条接线钉住。</p>
 *
 * <p>同时锁定三个字段的「0 = 未设置」语义：{@code timeout} 在 {@code AiConfig} 里是有默认值
 * 的非空字段（120s），无法表达「未设置」，所以这里的超时另用 {@code modelDefaultTimeoutSeconds}
 * 以 0 表示未设置，避免拿 120 当哨兵值造成静默改写。</p>
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
            "general.modelDefaults.timeout",
            "general.modelDefaults.timeoutPlaceholder",
            "general.modelDefaults.concurrencyInvalid",
            "general.modelDefaults.timeoutInvalid",
            "general.modelDefaults.desc",
            // 并入「对话策略」的两项（已更名）
            "general.chatDefaults.thinkingMode",
            "general.chatDefaults.reasoningEffort",
            // 模型编辑表单的子代理并发数
            "llm.concurrency",
            "llm.concurrencyPlaceholder",
            "llm.concurrencyInvalid"
    };

    @Test
    @DisplayName("实体：三个字段默认 0（= 未设置），不引入第二套哨兵值")
    void entityDefaultsToZero() {
        GeneralGroupDo g = new GeneralGroupDo();

        assertTrue(g.getModelDefaultContextLength() == 0L, "默认上下文长度必须是 0（未设置）");
        assertTrue(g.getModelDefaultConcurrency() == 0, "默认并发数必须是 0（未设置，回退引擎缺省）");
        assertTrue(g.getModelDefaultTimeoutSeconds() == 0L, "默认超时必须是 0（未设置），不能用 120 当哨兵");
    }

    @Test
    @DisplayName("页面：「对话默认」并入「对话策略」，「模型默认」三列独立成卡")
    void pageHasModelDefaultsCard() throws IOException {
        String html = resourceText("/static/web.html");

        assertTrue(html.contains("data-i18n=\"general.modelDefaults.title\""), "必须新增「模型默认」卡片标题");
        assertTrue(html.contains("id=\"generalModelDefaultContextLength\""), "必须有上下文长度输入框");
        assertTrue(html.contains("id=\"generalModelDefaultConcurrency\""), "必须有并发数输入框");
        assertTrue(html.contains("id=\"generalModelDefaultTimeout\""), "必须有超时输入框");

        // 「对话默认」不再有独立卡片：默认思考模式/默认推理强度直接落在「对话策略」卡内
        int conversationCard = html.indexOf("data-i18n=\"general.conversation.title\"");
        int thinkingMode = html.indexOf("id=\"generalDefaultThinkingMode\"");
        int modelDefaults = html.indexOf("data-i18n=\"general.modelDefaults.title\"");
        assertTrue(conversationCard > 0 && thinkingMode > 0 && modelDefaults > 0, "三处都必须存在");
        assertTrue(conversationCard < thinkingMode, "默认思考模式必须在「对话策略」卡内");
        assertTrue(thinkingMode < modelDefaults, "「模型默认」必须排在「对话策略」之后");
        assertFalse(html.contains("data-i18n=\"general.chatDefaults.title\""),
                "「对话默认」卡已并入「对话策略」，不应再残留卡片标题");
    }

    @Test
    @DisplayName("并发数必须热更新引擎：保存与启动两条路径都要接上")
    void concurrencyWiredToEngine() throws IOException {
        String controller = javaSource("api/web/settings/WebSettingsController.java");
        assertTrue(controller.contains("engine.setSubagentDefaultConcurrency(g.getModelDefaultConcurrency())"),
                "保存通用设置时必须把并发数推给引擎，否则配置不生效");

        String manager = javaSource("workspace/WorkspaceManager.java");
        assertTrue(manager.contains(".subagentDefaultConcurrency(wsSettings.getGeneral().getModelDefaultConcurrency())"),
                "启动构建引擎时必须带上并发数，否则重启后回落到引擎缺省值");
    }

    @Test
    @DisplayName("模型默认必须真的被用起来：手工添加、供应商同步、供应商生成三条新建路径")
    void modelDefaultsAppliedOnCreate() throws IOException {
        String controller = javaSource("api/web/settings/LlmSettingController.java");

        assertTrue(controller.contains("protected void applyModelDefaults(ModelDo modelDo, boolean overwriteTimeout)"),
                "必须存在统一的模型默认套用入口");

        // 手工添加模型
        assertTrue(controller.contains("applyModelDefaults(config, false)"),
                "手工添加模型必须套用模型默认（超时交给前端预填，后端不覆盖）");

        // 供应商同步 / 生成：服务端刚 new 出来的模型，允许无条件套用默认超时
        int applyCount = controller.split("applyModelDefaults\\(modelDo, true\\)", -1).length - 1;
        assertTrue(applyCount >= 2, "供应商 sync-models 与 providersGenerate 都要套用，实际命中: " + applyCount);

        // 编辑模型时不得把新建时套用的并发数抹成 0
        assertTrue(controller.contains("config.getConcurrency() <= 0 && oldConfig != null && oldConfig.getConcurrency() > 0"),
                "模型编辑提交未含并发数时，必须继承旧值");
    }

    @Test
    @DisplayName("前端接线：读取回填、保存提交、新建模型预填")
    void frontendWiring() throws IOException {
        String general = resourceText("/static/js/app-settings-general.js");
        assertTrue(general.contains("modelDefaultContextLength"), "通用设置必须回填/提交上下文长度");
        assertTrue(general.contains("modelDefaultConcurrency"), "通用设置必须回填/提交并发数");
        assertTrue(general.contains("modelDefaultTimeoutSeconds"), "通用设置必须回填/提交超时秒数");
        assertTrue(general.contains("function parseTimeoutSeconds"), "超时输入需支持 120 / 120s / 2m / 1h");

        String llm = resourceText("/static/js/app-settings-llm.js");
        assertTrue(llm.contains("function applyModelDefaults"), "新建模型时必须用模型默认预填");
        assertTrue(llm.contains("applyModelDefaults()"), "新建模型入口必须调用预填");
    }

    @Test
    @DisplayName("i18n：22 个语言包都要有新增键，且不再残留「新建对话默认」")
    void i18nComplete() throws IOException {
        String zh = resourceText("/static/i18n/zh-CN.json");
        for (String key : I18N_KEYS) {
            assertTrue(zh.contains("\"" + key + "\""), "zh-CN 缺少键: " + key);
        }

        assertFalse(zh.contains("\"general.chatDefaults.title\""),
                "「对话默认」卡已并入「对话策略」，该键不再使用");
        assertTrue(zh.contains("\"general.chatDefaults.thinkingMode\": \"默认思考模式\""),
                "「思考模式」必须更名为「默认思考模式」");
        assertTrue(zh.contains("\"general.chatDefaults.reasoningEffort\": \"默认推理强度\""),
                "「推理强度」必须更名为「默认推理强度」");
        assertTrue(zh.contains("\"general.modelDefaults.concurrency\": \"子代理并发数\""),
                "「并发数」必须更名为「子代理并发数」");

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
