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
public class SystemContextPayload implements Serializable {
    private Integer tokens;
    private Integer count;
    private Long contextLimit;
    private Double cacheRate;
}
