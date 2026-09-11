package org.noear.solon.codecli.portal.web;

import java.io.Serializable;

/**
 * 一条任务级运行中插话。
 *
 * <p>ID 由前端在提交前生成，并随 applied/dropped 事件原样返回，用于在重复文本、取消请求
 * 与状态事件并发到达时精确定位同一条插话。</p>
 */
public class SteerMessage implements Serializable {
    private final String id;
    private final String text;

    public SteerMessage(String id, String text) {
        this.id = id;
        this.text = text;
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }
}
