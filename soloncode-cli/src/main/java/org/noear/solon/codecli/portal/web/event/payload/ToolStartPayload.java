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
public class ToolStartPayload implements Serializable {
    private String callId;
    private String name;
    private String title;
    private Map<String, Object> args;
}
