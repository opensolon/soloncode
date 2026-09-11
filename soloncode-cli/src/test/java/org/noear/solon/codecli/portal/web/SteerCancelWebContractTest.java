package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务排队中的待生效插话必须具备真正的后端撤销链路。 */
public class SteerCancelWebContractTest {
    @Test
    public void pendingSteerHasCancelableIdentityAndAction() throws IOException {
        String javascript = resourceText("/static/js/app-streaming.js");

        assertTrue(javascript.contains("body.append('steerId', steerId)"));
        assertTrue(javascript.contains("fetch('/web/chat/steer/cancel'"));
        assertTrue(javascript.contains("data-act=\"cancel-steer\""));
        assertTrue(javascript.contains("else if (act === 'cancel-steer') cancelSteerMessage(sess, qid, false)"));
        assertTrue(javascript.contains("(sess.messageQueue || []).length + (sess.steerPending || []).length"),
                "清空按钮必须同时统计普通排队和待生效插话");
        assertFalse(javascript.contains("if (!sess || !sess.messageQueue || !sess.messageQueue.length) return;"),
                "只有待生效插话时也必须允许点击清空");
    }

    @Test
    public void terminalEventsUseIdsAndIgnoreCanceledMessages() throws IOException {
        String javascript = resourceText("/static/js/app-streaming.js");

        assertTrue(javascript.contains("var items = (p && p.items) || []"));
        assertTrue(javascript.contains("incoming.id && isSteerResolved(sess, incoming.id)"));
        assertTrue(javascript.contains("removePendingSteer(sess, incoming.id, incoming.text)"));
        assertTrue(javascript.contains("{items: fallbackItems}"),
                "流结束兜底必须使用提交时的 ID 快照，不能按当前全量文本回退");
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream in = SteerCancelWebContractTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
