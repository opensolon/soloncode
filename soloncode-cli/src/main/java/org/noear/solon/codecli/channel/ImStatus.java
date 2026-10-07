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
 * IM 交互状态信号（非聊天内容）。
 *
 * <p>web 用户能看到排队、loading、工具过程；IM 用户此前只能「盲发」。
 * 这些信号用于补齐 IM 的感知，属于「状态」而非「消息」：它们不是会话内容，
 * 不参与历史记录，也不走流式通道（IM 只接收非流式的 ReasonEndEvent / RunEndEvent，
 * 见 {@code WebStreamBuilder.replyPartialToOriginChannel} 与 {@code replyToBoundChannel}）。</p>
 *
 * <p>信号由统一出口 {@code WebStreamBuilder.signalOriginChannel} 只投递给入站来源端，
 * 各通道只做渲染与能力降级（微信 typing 已表达「处理中」，可忽略 ACCEPTED）。</p>
 *
 * @author noear 2026/5/9 created
 */
public enum ImStatus {
    /**
     * 空闲受理成功：收到，马上开始处理
     */
    ACCEPTED,
    /**
     * 会话繁忙、输入已作为新任务进入统一队列
     */
    QUEUED,
    /**
     * 会话繁忙、输入已插话到正在运行的任务（下一个采样边界生效）
     */
    STEERED,
    /**
     * 长任务心跳：受理后超过阈值仍无终态
     */
    LONG_RUNNING,
    /**
     * 入队失败（队列已满等），本轮未被接收
     */
    REJECTED
}
