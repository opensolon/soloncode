package org.noear.solon.codecli.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话 ID 形态互斥：同一个 ID 最多被一种形态认领。有人改宽某个谓词的边界时这里直接失败，
 * 而不是静默抢走别的入口的会话（或把主会话当子任务会话不缓存、过期清掉）。
 */
class SessionIdOwnershipTest {
    private static final String TASK_ID = "task-0123456789abcdef0123456789abcdef";

    private static List<String> claimants(String id) {
        List<String> who = new ArrayList<>();
        if (SessionIdOwnership.isCli(id)) {
            who.add("cli");
        }
        if (SessionIdOwnership.isWeb(id)) {
            who.add("web");
        }
        if (SessionIdOwnership.isDesktop(id)) {
            who.add("desktop");
        }
        if (SessionIdOwnership.isSubtask(id)) {
            who.add("subtask");
        }
        return who;
    }

    @Test
    void eachIdIsClaimedByAtMostOneShape() {
        String[] samples = {
                "cli", "web-1732400000000", "web-abc", "auto-daily", "123456", "123456789012345678",
                TASK_ID, "task-board", "task-", "task-0123456789ABCDEF0123456789abcdef",
                "0123456789abcdef0123456789abcdef", "acp-1", "", null
        };
        for (String id : samples) {
            assertTrue(claimants(id).size() <= 1, id + " 被多种形态认领: " + claimants(id));
        }
    }

    @Test
    void subtaskRequiresFullTaskIdFormat() {
        assertEquals(1, claimants(TASK_ID).size());
        assertTrue(SessionIdOwnership.isSubtask(TASK_ID));
        assertFalse(SessionIdOwnership.isSubtask("task-board"), "只是前缀相同不算子任务会话");
        assertFalse(SessionIdOwnership.isSubtask("task-"));
        assertFalse(SessionIdOwnership.isSubtask(null));
        assertFalse(SessionIdOwnership.isSubtask("web-" + TASK_ID));
    }
}
