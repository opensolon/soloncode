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
    public void asyncCancelKeepsTruthfulStateAndNeverGuessesDropped() throws IOException {
        String javascript = resourceText("/static/js/app-streaming.js");

        assertTrue(javascript.contains("var submittedRunId = sess.currentRunId || null"),
                "提交与取消必须使用同一个 runId 快照");
        assertTrue(javascript.contains("status: 'submitting'"),
                "HTTP 回执前也必须纳入可取消范围");
        assertTrue(javascript.contains("item.status = 'pending';\n                if (item.discardRequested) cancelSteerMessage(sess, item.id, true)"),
                "提交成功后必须先退出 submitting，再真正发起取消请求");
        assertFalse(javascript.contains("if (item.status === 'submitting') {\n            item.status = 'canceling';"),
                "提交未确认时不能抢先进入 canceling，否则成功回调会被防重复分支拦截");
        assertTrue(javascript.contains("if (item.discardRequested) cancelSteerMessage(sess, item.id, true)"),
                "Stop 后迟到的成功回执只能继续撤销");
        assertTrue(javascript.contains("item.status = 'unknown'"),
                "取消失败不能伪装成已经删除");
        assertTrue(javascript.contains("incoming.id && isSteerResolved(sess, incoming.id)"));
        assertFalse(javascript.contains("handleSteerEvent(sess, 'system.steer_dropped', {items:"),
                "超时不能凭空推导 dropped 并把可能已执行的插话再次入队");
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
