package org.noear.solon.codecli.portal.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsGateRunModeTest {
    @Test
    void approvalModeProtectsFileChangesAndCommands() {
        assertTrue(WsGate.requiresDesktopApproval("default", "write"));
        assertTrue(WsGate.requiresDesktopApproval("default", "edit"));
        assertTrue(WsGate.requiresDesktopApproval("default", "bash"));
        assertFalse(WsGate.requiresDesktopApproval("default", "read"));
    }

    @Test
    void autoModeBypassesAllApprovals() {
        assertFalse(WsGate.requiresDesktopApproval("auto", "write"));
        assertFalse(WsGate.requiresDesktopApproval("auto", "edit"));
        assertFalse(WsGate.requiresDesktopApproval("auto", "bash"));
        assertFalse(WsGate.requiresDesktopApproval("auto", "read"));
    }

    @Test
    void planAndGoalDoNotInstallDesktopApprovalPolicy() {
        assertFalse(WsGate.requiresDesktopApproval("plan", "write"));
        assertFalse(WsGate.requiresDesktopApproval("plan", "bash"));
        assertFalse(WsGate.requiresDesktopApproval("goal", "write"));
        assertFalse(WsGate.requiresDesktopApproval("goal", "bash"));
    }

    @Test
    void fullModeBypassesAllApprovals() {
        assertFalse(WsGate.requiresDesktopApproval("full", "write"));
        assertFalse(WsGate.requiresDesktopApproval("full", "edit"));
        assertFalse(WsGate.requiresDesktopApproval("full", "bash"));
        assertFalse(WsGate.requiresDesktopApproval("full", "read"));
    }

    @Test
    void missingOrUnknownModeFailsClosed() {
        assertEquals("default", WsGate.normalizeDesktopRunMode(null));
        assertEquals("default", WsGate.normalizeDesktopRunMode("unexpected"));
        assertTrue(WsGate.requiresDesktopApproval(null, "write"));
        assertTrue(WsGate.requiresDesktopApproval("unexpected", "bash"));
    }
}
