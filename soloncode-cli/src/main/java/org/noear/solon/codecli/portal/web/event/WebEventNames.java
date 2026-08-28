package org.noear.solon.codecli.portal.web.event;

/**
 * SAEP 2.0 (Solon Agent Event Protocol) 事件点分命名常量
 *
 * @author noear
 */
public interface WebEventNames {
    // message 命名空间
    String MESSAGE_DELTA = "message.delta";

    // thought 命名空间
    String THOUGHT_DELTA = "thought.delta";

    // tool 命名空间
    String TOOL_START = "tool.start";
    String TOOL_END = "tool.end";

    // hitl 命名空间
    String HITL_PENDING = "hitl.pending";
    String HITL_RESOLVED = "hitl.resolved";

    // task 命名空间
    String TASK_START = "task.start";
    String TASK_DONE = "task.done";

    // ui 命名空间（协议层 UI 扩展：结构化 UI 块渲染 / 增量更新 / 交互回传）
    String UI_RENDER = "ui.render";
    String UI_PATCH = "ui.patch";
    String UI_ACTION = "ui.action";

    // system 命名空间
    String SYSTEM_TRACE = "system.trace";
    String SYSTEM_CONTEXT = "system.context";
    String SYSTEM_FILER_CHANGE = "system.filer_change";
    String SYSTEM_USER_INPUT = "system.user_input";
    String SYSTEM_COMMAND = "system.command";
    String SYSTEM_REWIND = "system.rewind";
    String SYSTEM_RESET = "system.reset";
    String SYSTEM_DONE = "system.done";
    String SYSTEM_ERROR = "system.error";
    String SYSTEM_STEER_APPLIED = "system.steer_applied";
    String SYSTEM_STEER_DROPPED = "system.steer_dropped";
}
