package org.noear.solon.codecli.api.web.event.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemErrorPayload implements Serializable {
    private String code;
    private String message;
}
