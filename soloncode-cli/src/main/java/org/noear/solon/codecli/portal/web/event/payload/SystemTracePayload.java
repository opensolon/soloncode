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
public class SystemTracePayload implements Serializable {
    private String model;
    private Long totalTokens;
    private Long inputTokens;
    private Long outputTokens;
    private Long elapsedSeconds;
    private String finalAnswer;
}
