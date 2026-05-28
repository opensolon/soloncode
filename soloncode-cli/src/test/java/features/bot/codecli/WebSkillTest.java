package features.bot.codecli;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.portal.web.WebSettingsController;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.handle.Result;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSettingsController 单元测试
 */
public class WebSkillTest {

    private WebSettingsController controller;

    @BeforeEach
    public void setUp() throws Exception {
        controller = new WebSettingsController(null);
    }

    @Test
    public void skills_proxy_returns_non_null() throws Exception {
        Context ctx = new ContextEmpty();
        // skillsProxy 调用外部网络，可能成功也可能超时失败，但不抛异常即通过
        Result<String> result = controller.skillsProxy(ctx, "trending", "", 10, 10);
        System.out.println(result);
        Assertions.assertEquals(200, result.getCode());
    }

    @Test
    public void skills_proxy_invalid_action_falls_to_trending() throws Exception {
        Context ctx = new ContextEmpty();
        Result<String> result = controller.skillsProxy(ctx, "unknown", "", 10, 10);
        System.out.println(result);
        Assertions.assertEquals(200, result.getCode());
    }
}