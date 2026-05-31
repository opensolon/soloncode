package org.noear.solon.codecli.config;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 *
 * @author noear 2026/5/31 created
 *
 */
@Getter
@Setter
public class GeneralSettings implements Serializable {
    //上下文压缩触发消息数（达到这个数，就开始触发）
    private Integer summaryWindowSize;
    //上下文压缩触发词元数（达到这个数，就开始触发）
    private Integer summaryWindowToken;
    //启用消盒模式
    private Boolean sandboxMode;
}
