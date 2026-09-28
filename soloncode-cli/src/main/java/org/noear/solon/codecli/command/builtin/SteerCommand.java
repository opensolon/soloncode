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
package org.noear.solon.codecli.command.builtin;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.codecli.command.WebCommandContext;
import org.noear.solon.codecli.portal.web.SessionQueue;
import org.noear.solon.codecli.portal.web.SteerInterceptor;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.core.util.Assert;

/**
 * /steer 命令 - 向正在执行的任务插入实时补充（插话）。
 *
 * <p>CLI 前端用 Enter 提交插话、Web 前端调 /web/chat/steer，但 IM 渠道（微信/飞书/钉钉）
 * 缺少插话途径。此命令为 IM 场景提供统一的插话能力：把补充消息注入<b>当前</b>任务的工作记忆，
 * 由 {@link SteerInterceptor} 在下一个采样边界消费，不打断进行中的模型流与工具调用。</p>
 *
 * <p>{@link #runnableWhenBusy()} 返回 true，使其可穿透 WebGate 的忙碌检查、在任务执行中被受理。
 * 若邮箱已满则自动降级为排队；若会话空闲（无进行中的任务）则当作普通消息直接发送。</p>
 *
 * @author noear
 */
public class SteerCommand implements Command {
    @Override
    public String name() {
        return "steer";
    }

    @Override
    public String description() {
        return "向正在执行的任务插入实时补充（插话）";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/steer 补充一个要求：顺便更新 README",
                "/steer 注意别改动测试代码"
        };
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;
    }

    @Override
    public void execute(CommandContext ctx) {
        String text = ctx.getArgsJoined();
        if (Assert.isEmpty(text)) {
            ctx.println("用法：/steer <补充内容>（在任务执行中向当前任务插入实时补充）");
            return;
        }

        AgentSession session = ctx.getSession();
        String source = "command";
        String sourceUserId = null;
        String replyTarget = null;
        String messageId = null;
        if (ctx instanceof WebCommandContext) {
            WebCommandContext web = (WebCommandContext) ctx;
            String contextSource = web.getSource();
            if (Assert.isNotEmpty(contextSource)) source = contextSource;
            sourceUserId = web.getSourceUserId();
            replyTarget = web.getReplyTarget();
            messageId = web.getMessageId();
        }
        if (session != null && ctx.getEngine() != null) {
            SessionQueue.bindStorage(session, org.noear.solon.codecli.workspace.WorkspaceDataUtil.sessionsPath(ctx.getEngine().getWorkspace()).resolve(session.getSessionId()));
        }
        SteerInterceptor.SteerResult result = SteerInterceptor.steer(session, null, null, text, source,
                sourceUserId, replyTarget, messageId, null);

        switch (result.getStatus()) {
            case STEERED:
                ctx.println("已插话，将在下一步注入当前任务（待生效 " + result.getQueued() + " 条）");
                break;
            case BOX_FULL: {
                // 插话邮箱已满：降级为排队，任务结束后自动作为新任务发送
                int n = SessionQueue.enqueue(session, text, source, sourceUserId, replyTarget, messageId);
                ctx.println(n > 0
                        ? "插话邮箱已满，已改为排队（第 " + n + " 位，当前任务结束后自动发送）"
                        : "插话邮箱与排队均已满，请稍后再试");
                break;
            }
            case NOT_RUNNING:
            case TURN_CHANGED:
                // runId 可能尚未建立，但 WebGate 已接纳输入或占有任务槽位。
                // 与 SessionQueue 的入队共用 attrs 锁，缩小忙态检查到入队之间的窗口。
                if (session != null) {
                    synchronized (session.attrs()) {
                        boolean busy = WebGate.isInputAdmitting(session)
                                || WebGate.hasActiveStream(session);
                        if (busy) {
                            int n = SessionQueue.enqueue(session, text, source, sourceUserId, replyTarget, messageId);
                            ctx.println(n > 0
                                    ? "当前任务尚无法插话，已改为排队（第 " + n + " 位，当前任务结束后自动发送）"
                                    : "插话未生效且排队失败（队列已满或持久化失败），请稍后再试");
                            break;
                        }
                    }
                }
                // 确认空闲时保留原有普通消息直发语义。
                ctx.runAgentTask(text, null);
                break;
            case TEXT_TOO_LONG:
                ctx.println("插话内容过长（上限 " + SteerInterceptor.MAX_TEXT_LENGTH + " 字符）");
                break;
            default:
                ctx.println("插话未生效：" + result.getStatusCode());
        }
    }
}
