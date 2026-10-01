package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebControllerSessionIdTest {
    private boolean valid(String value) throws Exception {
        Method method = WebController.class.getDeclaredMethod("isValidSessionId", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, value);
    }

    @Test
    void acceptsWebAndDesktopSessionIds() throws Exception {
        assertTrue(valid("web-demo_1"));
        assertTrue(valid("42"));
        assertFalse(valid("0"));
    }

    @Test
    void acceptsAutomationSessionIds() throws Exception {
        // auto- 后缀为 UUID 十六进制（去横杠后 32 位）
        assertTrue(valid("auto-0123456789abcdef0123456789abcdef"));
        assertFalse(valid("auto-"));
        assertFalse(valid("auto-abc"));
        assertFalse(valid("auto-0123456789ABCDEF"));
        assertFalse(valid("auto/../secret"));
    }

    @Test
    void rejectsTraversalAndUnexpectedDesktopIds() throws Exception {
        assertFalse(valid("../42"));
        assertFalse(valid("1/../../secret"));
        assertFalse(valid("temp-42"));
        assertFalse(valid("42.json"));
    }
}
