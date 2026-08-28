package org.noear.solon.codecli.portal.web.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.noear.solon.codecli.portal.web.event.payload.*;
import org.noear.solon.codecli.portal.web.event.payload.UiPatchPayload;
import org.noear.solon.codecli.portal.web.event.payload.UiRenderPayload;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * SAEP 2.0 (Solon Agent Event Protocol) 统一事件信封
 *
 * @param <T> 专属载荷类型
 * @author noear
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebEvent<T> implements Serializable {

    public static final WebEvent<?> EMPTY = new WebEvent<>();

    /** 点分命名空间事件名，例如 "message.delta", "tool.start" */
    private String event;

    /** 会话 ID */
    private String sessionId;

    /** 单次任务运行的跟踪 ID（对应底层 AgentEvent 的 runId） */
    private String runId;

    /** 子任务 ID (可选)：有值代表该事件归属某个 task 组（子代理任务） */
    private String taskId;

    /** 推理轮次标识 (可选)：同一 reasonId 的思考/正文/工具事件归属同一 reason 组，
     *  前端据此将同一轮输出分组，避免后一轮最终消息错接到前一轮分组。 */
    private String reasonId;

    /** 触发该事件的代理名称 (可选) */
    private String agentName;

    /** 毫秒时间戳 */
    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    /** 强类型专属载荷 */
    private T payload;

    public static boolean isNotEmpty(WebEvent<?> event) {
        return event != null && event.event != null;
    }

    public static <T> WebEvent<T> of(String event, T payload) {
        WebEvent<T> evt = new WebEvent<>();
        evt.setEvent(event);
        evt.setPayload(payload);
        return evt;
    }

    public static WebEvent<Void> ofDone() {
        return of(WebEventNames.SYSTEM_DONE, null);
    }

    public static WebEvent<SystemErrorPayload> ofError(String message) {
        return ofError(null, message);
    }

    public static WebEvent<SystemErrorPayload> ofError(String code, String message) {
        return of(WebEventNames.SYSTEM_ERROR, SystemErrorPayload.builder()
                .code(code)
                .message(message)
                .build());
    }

    public static WebEvent<SystemErrorPayload> ofError(Throwable err) {
        String msg = (err != null && err.getMessage() != null) ? err.getMessage() : "未知错误";
        return ofError(null, msg);
    }

    public static WebEvent<MessagePayload> ofText(String text) {
        return ofText(null, text);
    }

    public static WebEvent<MessagePayload> ofText(String reasonId, String text) {
        WebEvent<MessagePayload> evt = of(WebEventNames.MESSAGE_DELTA, MessagePayload.builder().delta(text).build());
        evt.setReasonId(reasonId);
        return evt;
    }

    public static WebEvent<ThoughtPayload> ofReason(String reasonId, String text) {
        WebEvent<ThoughtPayload> evt = of(WebEventNames.THOUGHT_DELTA, ThoughtPayload.builder().delta(text).build());
        evt.setReasonId(reasonId);
        return evt;
    }

    public static WebEvent<ThoughtPayload> ofReason(String text) {
        return ofReason(null, text);
    }

    public static WebEvent<ToolStartPayload> ofToolCallStart(String toolName, String toolTitle, Map<String, Object> args) {
        return of(WebEventNames.TOOL_START, ToolStartPayload.builder()
                .name(toolName)
                .title(toolTitle)
                .args(args)
                .build());
    }

    public static WebEvent<ToolEndPayload> ofToolCallEnd(String text) {
        return of(WebEventNames.TOOL_END, ToolEndPayload.builder()
                .result(text)
                .build());
    }

    public static WebEvent<HitlPayload> ofHitl(String toolName, String toolTitle, Map<String, Object> args,
                                              String command, String callId, String comment) {
        return of(WebEventNames.HITL_PENDING, HitlPayload.builder()
                .toolName(toolName)
                .toolTitle(toolTitle)
                .args(args)
                .command(command)
                .callId(callId)
                .comment(comment)
                .build());
    }

    public static WebEvent<TaskDonePayload> ofTaskDone(String status) {
        return of(WebEventNames.TASK_DONE, TaskDonePayload.builder().status(status).build());
    }

    public static WebEvent<SystemTracePayload> ofTrace(String model, Long totalTokens, Long elapsedSeconds, String finalAnswer) {
        return of(WebEventNames.SYSTEM_TRACE, SystemTracePayload.builder()
                .model(model)
                .totalTokens(totalTokens)
                .elapsedSeconds(elapsedSeconds)
                .finalAnswer(finalAnswer)
                .build());
    }

    public static WebEvent<SystemRewindPayload> ofRewind(int count) {
        return of(WebEventNames.SYSTEM_REWIND, SystemRewindPayload.builder().count(count).build());
    }

    public static WebEvent<SystemCommandPayload> ofCommand(String text) {
        return of(WebEventNames.SYSTEM_COMMAND, SystemCommandPayload.builder().command(text).build());
    }

    public static WebEvent<Void> ofResetStream() {
        return of(WebEventNames.SYSTEM_RESET, null);
    }

    public static WebEvent<SystemUserInputPayload> ofUserInput(String text, String source) {
        return of(WebEventNames.SYSTEM_USER_INPUT, SystemUserInputPayload.builder()
                .text(text)
                .source(source)
                .build());
    }

    public static WebEvent<SteerPayload> ofSteerApplied(String runId, List<String> texts) {
        return buildSteerEvent(WebEventNames.SYSTEM_STEER_APPLIED, runId, texts);
    }

    public static WebEvent<SteerPayload> ofSteerDropped(String runId, List<String> texts) {
        return buildSteerEvent(WebEventNames.SYSTEM_STEER_DROPPED, runId, texts);
    }

    private static WebEvent<SteerPayload> buildSteerEvent(String event, String runId, List<String> texts) {
        WebEvent<SteerPayload> evt = of(event, SteerPayload.builder()
                .runId(runId)
                .texts(texts)
                .build());
        evt.setRunId(runId);
        return evt;
    }

    public static WebEvent<UiRenderPayload> ofUiRender(UiRenderPayload payload) {
        return of(WebEventNames.UI_RENDER, payload);
    }

    public static WebEvent<UiPatchPayload> ofUiPatch(UiPatchPayload payload) {
        return of(WebEventNames.UI_PATCH, payload);
    }

    public static String toSourceLabel(String source) {
        if (source == null) return "Web";
        switch (source) {
            case "wechat": return "微信";
            case "feishu": return "飞书";
            case "dingtalk": return "钉钉";
            case "web": return "Web";
            case "steer": return "插话";
            default: return source;
        }
    }
}
