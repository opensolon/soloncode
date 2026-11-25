package org.noear.solon.codecli.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.command.builtin.ClearCommand;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.util.TraceUtil;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实引擎 + 真实 {@link SessionManager} 的子任务会话端到端：
 * 子会话建在父会话目录下、别的父会话拿不到、删父会话目录后旧 task_id 失效且不留空壳。
 *
 * <p>假模型指向不可达地址：子代理调用模型必然失败，但子会话照样建出来并带 task_id（可续跑失败）。</p>
 */
class SubtaskSessionEndToEndTest {
    private static final Pattern TASK_ID = Pattern.compile("<task_id>(task-[0-9a-f]{32})</task_id>");

    /** 关掉子代理内部重试，避免用例被重试间隔拖慢 */
    private static final HarnessExtension NO_RETRY = new HarnessExtension() {
        @Override
        public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend o) {
            o.retryConfig(0, 0);
        }
    };

    @TempDir
    Path workspace;

    private HarnessEngine engine;

    @AfterEach
    void cleanup() throws IOException {
        if (engine != null) {
            engine.shutdown();
        }
        // 会话数据落在 ~/.soloncode/workspaces/<工作区标识>/ 下，用完清掉
        deleteTree(sessionsRoot().getParent());
    }

    private Path sessionsRoot() {
        return WorkspaceDataUtil.sessionsPath(workspace.toString());
    }

    private static void deleteTree(Path dir) throws IOException {
        if (dir != null && Files.exists(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private SessionManager start() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        SessionManager manager = new SessionManager(workspace.toString());
        engine = HarnessEngine.of(workspace.toString(), workspace.resolve(".home").toString())
                .sessionProvider(manager)
                .modelAdd(model)
                .defaultModel("fake")
                .extensionAdd(NO_RETRY)
                .build();
        return manager;
    }

    /** 让会话的「当前轨迹」指向一条空轨迹（TaskTalent 从这里取父轨迹） */
    private static void bindTrace(AgentSession session) throws Exception {
        ReActTrace trace = new ReActTrace();
        Field options = ReActTrace.class.getDeclaredField("options");
        options.setAccessible(true);
        options.set(trace, new ReActOptions((ChatModel) null));
        session.getContext().put(Agent.KEY_CURRENT_UNIT_TRACE_KEY, "__e2e_trace");
        session.getContext().put("__e2e_trace", trace);
        session.getContext().put(TraceUtil.TRACE_KEY_MAIN, trace);
    }

    private ToolResult task(String parentId, String taskId) {
        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "explore";
        op.description = "d";
        op.prompt = taskId == null ? "p1" : "继续";
        op.task_id = taskId;
        return engine.getTaskTalent().task(op, workspace.toString(), parentId, null);
    }

    private static String taskIdOf(ToolResult r) {
        Matcher m = TASK_ID.matcher(r.getContent());
        assertTrue(m.find(), r.getContent());
        return m.group(1);
    }

    @Test
    void subtaskSessionLivesAndDiesWithItsParent() throws Exception {
        SessionManager manager = start();
        bindTrace(manager.getSession("web-p1"));
        bindTrace(manager.getSession("web-p2"));

        // 新建：子会话建在父会话目录下，不平铺、不进内存缓存
        String id = taskIdOf(task("web-p1", null));
        Path nested = sessionsRoot().resolve("web-p1").resolve(SessionManager.SUBTASKS_DIR).resolve(id);
        assertTrue(Files.isDirectory(nested), "子会话应建在父会话目录下");
        assertFalse(Files.exists(sessionsRoot().resolve(id)), "不再平铺在 sessions 下");
        assertNull(manager.getLoadedSession(id), "子任务会话不常驻内存");

        // 续跑：同一父会话接得上
        assertEquals(id, taskIdOf(task("web-p1", id)));

        // 别的父会话拿同一个 task_id：查无此任务，不给 task_id，也不在自己名下留空壳
        ToolResult other = task("web-p2", id);
        assertTrue(other.isError() && other.getContent().contains("<task_id>") == false, other.getContent());
        assertFalse(Files.exists(sessionsRoot().resolve("web-p2").resolve(SessionManager.SUBTASKS_DIR)));

        // 按宿主删除顺序删父会话：判可删 → removeSession → evict → 删目录，子会话随之消失
        assertTrue(engine.status(manager.getSession("web-p1")).isRemovable());
        engine.removeSession("web-p1");
        manager.evictSession("web-p1");
        deleteTree(sessionsRoot().resolve("web-p1"));
        assertFalse(Files.exists(nested), "删父会话目录即连带删除子会话");

        // 同名父会话重新建出来后，旧 task_id 已失效，且续跑只取不建，不会把子会话目录写回来
        bindTrace(manager.getSession("web-p1"));
        ToolResult stale = task("web-p1", id);
        assertTrue(stale.isError() && stale.getContent().contains("<task_id>") == false, stale.getContent());
        assertFalse(Files.exists(nested), "续跑查无此任务时不得建出子会话目录");
    }

    private void clear(AgentSession session) {
        CommandInput in = CommandInput.of(HarnessInput.of(session, "/clear"), ParsedCommand.parse("/clear"), null);
        CommandOutput out = (CommandOutput) Proxy.newProxyInstance(
                CommandOutput.class.getClassLoader(), new Class<?>[]{CommandOutput.class}, (p, m, a) -> null);
        new ClearCommand().execute(engine, in, out);
    }

    @Test
    void clearRemovesSubtaskSessionsOfThatSessionOnly() throws Exception {
        SessionManager manager = start();
        AgentSession p1 = manager.getSession("web-p1");
        bindTrace(p1);
        bindTrace(manager.getSession("web-p2"));

        String id1 = taskIdOf(task("web-p1", null));
        String id2 = taskIdOf(task("web-p2", null));
        Path p1Subtasks = sessionsRoot().resolve("web-p1").resolve(SessionManager.SUBTASKS_DIR);
        Path nested2 = sessionsRoot().resolve("web-p2").resolve(SessionManager.SUBTASKS_DIR).resolve(id2);
        assertTrue(Files.isDirectory(p1Subtasks.resolve(id1)));

        clear(p1);

        assertFalse(Files.exists(p1Subtasks), "/clear 连带删除本会话的 subtasks/");
        assertTrue(Files.isDirectory(sessionsRoot().resolve("web-p1")), "父会话目录保留");
        assertTrue(Files.isDirectory(nested2), "别的会话的子任务不受影响");

        // 清空后旧 task_id 已失效，续跑不会把子会话写回来
        bindTrace(p1);
        ToolResult stale = task("web-p1", id1);
        assertTrue(stale.isError() && stale.getContent().contains("<task_id>") == false, stale.getContent());
        assertFalse(Files.exists(p1Subtasks.resolve(id1)));
    }
}
