package org.noear.solon.codecli.portal.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDonePayload implements Serializable {
    private String taskId;
    private String parentTaskId;
    private String title;
    private String status;
    private boolean isMultitask;
}
