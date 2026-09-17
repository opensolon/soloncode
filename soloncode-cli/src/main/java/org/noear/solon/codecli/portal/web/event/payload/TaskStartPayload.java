package org.noear.solon.codecli.portal.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 子代理任务开始：与 {@link TaskDonePayload} 成对，让前端在子代理首个思考/工具事件之前
 * 就能建出 task-group 占位（子代理构建 + 首次模型调用期间界面不再空白）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStartPayload implements Serializable {
    private String taskId;
    private String parentTaskId;
    private String title;
    private String agentName;
    /** multitask 中的声明序号（1 起）；单任务恒为 1 */
    private int taskIndex;
    private boolean isMultitask;
}
