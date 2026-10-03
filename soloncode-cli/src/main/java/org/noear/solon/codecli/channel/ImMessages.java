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
 * <p>忙态回执还要交代「默认语义」：IM 忙时直发是「新任务排在后面」，web 忙时回车是
 * 「插话到当前任务」，两端默认不同，用户无从推断。故入队回执主动点明排队语义，
 * 并给出插话入口（/steer）与中断入口（/interrupt）；但同一轮只教学一次。</p>
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
     * 入队失败（队列满等）：此时唯一能自救的是中断当前任务，故直接给出命令。
     */
    public static final String REJECTED = "还有任务没忙完，暂时接不了新的。想立刻处理，可发送 /interrupt 中断当前任务";
    /**
     * 未绑定用户引导
     */
    public static final String HINT_UNBOUND = "还没有绑定会话，请先在 Web 端扫码绑定，然后我就能陪你聊了。";
    /**
     * 非文本消息回执
     */
    public static final String HINT_NON_TEXT = "我暂时只看得懂文字，换文字发给我吧。";

    /**
     * 忙态插话引导
     */
    public static final String STEER_HINT = "想补充或调整当前任务，可发送 /steer <内容>";
    /**
     * 忙态中断引导
     */
    public static final String INTERRUPT_HINT = "想中断当前任务，可发送 /interrupt";
    /**
     * 忙态命令引导（插话 + 中断），附在首条入队回执末尾。
     *
     * <p>这里刻意不引导 /queue：IM 忙态直发消息即自动排队，再教一个显式排队命令，
     * 反而会让用户以为「不敲命令消息就会丢」。/steer 才是 IM 真正缺的能力补位。</p>
     */
    public static final String BUSY_COMMAND_HINT = STEER_HINT + "；" + INTERRUPT_HINT;

    private ImMessages() {
    }

    /**
     * 入队回执：带上排队位次，并给出忙态命令引导。
     *
     * @param ahead 前面还有多少条待执行
     */
    public static String queued(int ahead) {
        return queued(ahead, true);
    }

    /**
     * 入队回执。
     *
     * <p>必须点明「已作为新任务排队」：忙态直发不是插话，不说清用户会以为自己的补充
     * 已经进了当前任务。</p>
     *
     * @param ahead           前面还有多少条待执行
     * @param withCommandHint 是否附上命令引导（同一轮忙态只提示一次）
     */
    public static String queued(int ahead, boolean withCommandHint) {
        int n = Math.max(0, ahead);
        StringBuilder sb = new StringBuilder("收到，已作为新任务排队，");
        if (n == 0) {
            // 前面没别的，说明马上轮到，不必提「还有 0 条」
            sb.append("马上轮到你了");
        } else {
            sb.append("前面还有 ").append(n).append(" 条，处理完就轮到你");
        }
        sb.append("。");
        if (withCommandHint) {
            sb.append(BUSY_COMMAND_HINT);
        }
        return sb.toString();
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
