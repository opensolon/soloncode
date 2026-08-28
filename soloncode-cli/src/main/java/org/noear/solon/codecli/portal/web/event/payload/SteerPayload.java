package org.noear.solon.codecli.portal.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 运行中插话（steer）状态载荷：
 * applied 表示已注入下一个推理回合的工作记忆；dropped 表示任务结束仍未消费、需前端转为排队。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SteerPayload implements Serializable {
    /** 关联任务运行 ID */
    private String runId;
    /** 插话文本列表 */
    private List<String> texts;
}
