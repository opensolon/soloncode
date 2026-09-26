package org.noear.solon.codecli.portal.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WsGateGoalModeTest {
    @Test
    void goalModeUsesMessageAsObjective() {
        assertEquals("持续修复测试", WsGate.extractGoalObjective("持续修复测试", "goal"));
    }

    @Test
    void goalCommandIsInterceptedBeforeGenericCommandDispatch() {
        assertEquals("你好", WsGate.extractGoalObjective("/goal 你好", "default"));
        assertEquals("", WsGate.extractGoalObjective(" /GOAL ", "default"));
    }

    @Test
    void similarlyNamedCommandsRemainGenericCommands() {
        assertNull(WsGate.extractGoalObjective("/goalkeeper 你好", "default"));
        assertNull(WsGate.extractGoalObjective("/help", "default"));
    }
}
