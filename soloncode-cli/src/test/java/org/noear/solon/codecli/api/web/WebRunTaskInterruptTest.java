package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.RunStartEvent;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.agent.TaskWrapEvent;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.api.web.event.payload.TaskDonePayload;
import org.noear.solon.codecli.api.web.pipeline.ToolPresentationFilter;
import org.noear.solon.codecli.api.web.pipeline.UiRenderFilter;
import org.noear.solon.codecli.api.web.pipeline.WebEventMapper;
import org.noear.solon.codecli.artifact.ImageReplyTurn;
import org.noear.solon.codecli.workspace.WorkspaceContext;

import org.objenesis.ObjenesisStd;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 一轮被取消或失败时，后端给还没结算的子任务组补发 task.done(interrupted)。
 *
 * <p>停止之后子代理事件不再下发，前端只能靠本页「是否点过停止」去猜：别的标签页、IM 或程序发起的停止，
 * 本页会把还在运行的组按完成结算（绿勾），刷新后才变成「被中断」。</p>
 */
class WebRunTaskInterruptTest {

    @TempDir
    Path tmp;

    private final AgentSession session = session();

    private static AgentSession session() {
        AgentSession session = mock(AgentSession.class);
        Map<String, Object> attrs = new HashMap<>();
        when(session.getSessionId()).thenReturn("s1");
        when(session.attrs()).thenReturn(attrs);
        return session;
    }

    private WebRunPipeline pipeline() {
        WebEventMapper mapper = new WebEventMapper(mock(WebStreamBuilder.class), mock(WorkspaceContext.class), session, null);
        return new WebRunPipeline(mock(WebStreamBuilder.class), mock(WorkspaceContext.class), session,
                new ImageReplyTurn(session, tmp.toString(), tmp.toString()), mapper, new ToolPresentationFilter(), new UiRenderFilter());
    }

    private static TaskWrapEvent wrap(String parentCallId, String taskId, AgentEvent real) {
        ReActTrace parent = mock(ReActTrace.class);
        when(parent.getRunId()).thenReturn("run-1");
        TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
        op.index = 1;
        op.agent_name = "explore";
        op.description = "查代码 " + taskId;
        return new TaskWrapEvent(parent, parentCallId, taskId, op, true, real);
    }

    private static TaskWrapEvent start(String parentCallId, String taskId) {
        return wrap(parentCallId, taskId, mock(RunStartEvent.class));
    }

    private static TaskWrapEvent end(String parentCallId, String taskId) {
        return wrap(parentCallId, taskId, mock(RunEndEvent.class));
    }

    @Test
    void onlyUnsettledGroupsGetInterruptedDoneOnce() {
        WebRunPipeline pipeline = pipeline();
        pipeline.map(start("call-1", "task-a"));
        pipeline.map(start("call-1", "task-b"));
        pipeline.map(end("call-1", "task-a"));

        List<WebEvent<?>> dones = pipeline.interruptOpenTasks();
        assertEquals(1, dones.size(), "已结束的组不补：" + dones);
        WebEvent<?> done = dones.get(0);
        assertEquals(WebEventNames.TASK_DONE, done.getEvent());
        assertEquals("task-b", done.getTaskId());
        assertEquals("call-1", done.getParentCallId(), "按调用定位组（同一 taskId 续跑时每次调用一个组）");
        assertEquals("run-1", done.getRunId());
        assertEquals("s1", done.getSessionId());
        assertEquals("explore", done.getAgentName());
        assertEquals("interrupted", ((TaskDonePayload) done.getPayload()).getStatus());

        assertTrue(pipeline.interruptOpenTasks().isEmpty(), "只补一次");
    }

    @Test
    void resumedTaskIsTrackedPerCall() {
        WebRunPipeline pipeline = pipeline();
        // 同一 taskId：第一次调用已结束，续跑（另一次调用）还在跑
        pipeline.map(start("call-1", "task-a"));
        pipeline.map(end("call-1", "task-a"));
        pipeline.map(start("call-2", "task-a"));

        List<WebEvent<?>> dones = pipeline.interruptOpenTasks();
        assertEquals(1, dones.size(), dones.toString());
        assertEquals("call-2", dones.get(0).getParentCallId());
    }

    @Test
    void cancelledRunEmitsInterruptedDone() throws Exception {
        List<String> emitted = runFinished(outcome(RunOutcome.End.CANCELLED, RunOutcome.CancelCause.CANCEL_STREAM));
        assertTrue(emitted.contains(WebEventNames.TASK_DONE + ":interrupted"), emitted.toString());
    }

    @Test
    void failedRunEmitsInterruptedDone() throws Exception {
        List<String> emitted = runFinished(outcome(RunOutcome.End.FAILED, null));
        assertTrue(emitted.contains(WebEventNames.TASK_DONE + ":interrupted"), emitted.toString());
    }

    @Test
    void completedRunDoesNotEmit() throws Exception {
        List<String> emitted = runFinished(outcome(RunOutcome.End.COMPLETED, null));
        assertFalse(emitted.contains(WebEventNames.TASK_DONE + ":interrupted"), "正常完成的一轮不补：" + emitted);
    }

    /** 本轮有一个已开始、未结束的子任务组，然后以 outcome 结束：返回下发给前端的事件（task.done 带状态） */
    private List<String> runFinished(RunOutcome outcome) throws Exception {
        List<String> emitted = new ArrayList<>();
        WebGate gate = mock(WebGate.class);
        doAnswer(inv -> {
            WebEvent<?> e = inv.getArgument(2);
            String name = e.getEvent();
            if (e.getPayload() instanceof TaskDonePayload) {
                name += ":" + ((TaskDonePayload) e.getPayload()).getStatus();
            }
            emitted.add(name);
            return null;
        }).when(gate).emitToClient(any(), anyString(), any());
        when(gate.getEventPublisher()).thenReturn(mock(WebEventPublisher.class));
        when(gate.getStreamBuilder()).thenReturn(mock(WebStreamBuilder.class));

        // HostRun 是 final、构造器包内：不经构造器建出 sink，只填 finished 用到的字段
        WebRunSink sink = new ObjenesisStd().newInstance(WebRunSink.class);
        set(sink, "gate", gate);
        set(sink, "ctx", mock(WorkspaceContext.class));
        set(sink, "session", session);
        set(sink, "sessionId", "s1");
        WebRunPipeline pipeline = pipeline();
        pipeline.map(start("call-1", "task-a"));
        set(sink, "pipeline", pipeline);
        set(sink, "begun", true);

        sink.finished(outcome);
        return emitted;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static RunOutcome outcome(RunOutcome.End end, RunOutcome.CancelCause cause) throws Exception {
        for (Constructor<?> c : RunOutcome.class.getDeclaredConstructors()) {
            if (c.getParameterCount() == 8) {
                c.setAccessible(true);
                return (RunOutcome) c.newInstance(end, cause, end == RunOutcome.End.FAILED ? new RuntimeException("x") : null,
                        null, null, true, false, null);
            }
        }
        throw new IllegalStateException("RunOutcome 构造器变了");
    }
}
