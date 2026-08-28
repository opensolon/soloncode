package org.noear.solon.codecli.portal.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HitlPayload implements Serializable {
    private String callId;
    private String toolName;
    private String toolTitle;
    private Map<String, Object> args;
    private String command;
    private String comment;
}
