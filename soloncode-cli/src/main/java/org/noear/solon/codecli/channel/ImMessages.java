/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.channel;

/**
 * IM 交互文案统一出口。
 *
 * <p>此前微信/飞书/钉钉各自维护 busy 提示等文案，容易分叉。收敛到此处后，
 * 三端（以及未来新增通道）复用同一份文案，{@link Channel#sendStatus} 的
 * detail 为空时由 {@link #textOf(ImStatus, String)} 兜底。</p>
 *
 * <p>文案面向 IM 使用场景，按聊天语气撰写：第一人称、口语、短句、不用
 * 「队列/位次/阈值」等系统术语，让用户一眼看懂「我这条消息怎么样了」。</p>
 *
 * @author noear 2026/5/9 created
 */
public final class ImMessages {
    /**
     * 空闲受理成功
     */
    public static final String ACCEPTED = "收到，马上开始处理";
    /**
     * 长任务心跳
     */
    public static final String LONG_RUNNING = "还在处理中，请再稍等一下";
    /**
     * 入队失败（队列满等）
     */
    public static final String REJECTED = "还有任务没忙完，暂时接不了新的，请稍后再发";
    /**
     * 未绑定用户引导
     */
    public static final String HINT_UNBOUND = "还没有绑定会话，请先在 Web 端扫码绑定，然后我就能陪你聊了。";
    /**
     * 非文本消息回执
     */
    public static final String HINT_NON_TEXT = "我暂时只看得懂文字，换文字发给我吧。";

    /**
     * 忙态下的中断引导，会附在入队回执末尾
     */
    public static final String INTERRUPT_HINT = "想中断当前任务，可发送 /interrupt";

    private ImMessages() {
    }

    /**
     * 入队回执：带上排队位次，并给出忙态下的中断引导。
     *
     * @param ahead 前面还有多少条待执行
     */
    public static String queued(int ahead) {
        int n = Math.max(0, ahead);
        if (n == 0) {
            // 前面没别的，说明马上轮到，不必提「还有 0 条」
            return "收到，已排上队，马上轮到你了。" + INTERRUPT_HINT;
        }
        return "收到，前面还有 " + n + " 条，处理完就轮到你。" + INTERRUPT_HINT;
    }

    /**
     * 解析状态对应的默认文案；detail 非空时优先采用 detail。
     *
     * @return null 表示该状态无需文本（由通道自行决定是否忽略）
     */
    public static String textOf(ImStatus status, String detail) {
        if (detail != null && !detail.trim().isEmpty()) {
            return detail;
        }
        if (status == null) {
            return null;
        }
        switch (status) {
            case ACCEPTED:
                return ACCEPTED;
            case LONG_RUNNING:
                return LONG_RUNNING;
            case REJECTED:
                return REJECTED;
            default:
                // QUEUED 需要位次，必须由调用方提供 detail
                return null;
        }
    }
}
