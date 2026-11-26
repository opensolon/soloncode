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
package org.noear.solon.codecli.api.web;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.api.web.event.payload.TaskDonePayload;
import org.noear.solon.codecli.api.web.event.payload.TaskStartPayload;
import org.noear.solon.codecli.api.web.pipeline.ToolPresentationFilter;
import org.noear.solon.codecli.api.web.pipeline.UiRenderFilter;
import org.noear.solon.codecli.api.web.pipeline.WebEventMapper;
import org.noear.solon.codecli.artifact.ImageReplyTurn;
import org.noear.solon.codecli.workspace.WorkspaceContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮运行的 SAEP 事件管道（每轮一个）：事件映射、展示过滤、图片回复轮次与失败回执。
 *
 * <p>由 {@link WebStreamBuilder#openPipeline} 在开流前装配（请求级选项已写入请求），
 * 开流后逐个 {@link #map} 事件；轮次结束时必须 {@link #cleanup}。</p>
 *
 * @author noear
 * @since 2026.11
 */
public final class WebRunPipeline {
    private final WebStreamBuilder builder;
    private final WorkspaceContext context;
    private final AgentSession session;
    private final ImageReplyTurn imageTurn;
    private final WebEventMapper mapper;
    private final ToolPresentationFilter toolFilter;
    private final UiRenderFilter uiRenderFilter;

    WebRunPipeline(WebStreamBuilder builder, WorkspaceContext context, AgentSession session, ImageReplyTurn imageTurn,
                   WebEventMapper mapper, ToolPresentationFilter toolFilter, UiRenderFilter uiRenderFilter) {
        this.builder = builder;
        this.context = context;
        this.session = session;
        this.imageTurn = imageTurn;
        this.mapper = mapper;
        this.toolFilter = toolFilter;
        this.uiRenderFilter = uiRenderFilter;
    }

    /**
     * 已发 task.start、还没发 task.done 的子任务组（键 parentCallId + taskId，按开始顺序）。
     * 与 map 同在运行回调里串行访问（HarnessHost 保证 event 与 finished 串行）。
     */
    private final Map<String, WebEvent<?>> openTasks = new LinkedHashMap<>();

    /** 把一个运行流事件映射为要下发的 SAEP 事件（已过滤空事件、已做展示处理） */
    public List<WebEvent<?>> map(AgentEvent event) {
        List<WebEvent<?>> out = new ArrayList<>();
        for (WebEvent<?> e : mapper.mapEvent(event)) {
            if (e != null && WebEvent.isNotEmpty(e)) {
                WebEvent<?> shown = uiRenderFilter.apply(toolFilter.apply(e));
                trackTask(shown);
                out.add(shown);
            }
        }
        return out;
    }

    private void trackTask(WebEvent<?> e) {
        if (e == null || e.getTaskId() == null) {
            return;
        }
        if (WebEventNames.TASK_START.equals(e.getEvent())) {
            openTasks.put(taskKey(e), e);
        } else if (WebEventNames.TASK_DONE.equals(e.getEvent())) {
            openTasks.remove(taskKey(e));
        }
    }

    private static String taskKey(WebEvent<?> e) {
        return e.getParentCallId() + "\n" + e.getTaskId();
    }

    /**
     * 本轮被取消或失败时，给还没结算的子任务组各补一个 status=interrupted 的 task.done（取出即清空，只补一次）。
     *
     * <p>停止之后子代理的事件不再下发，前端只能靠本页「是否点过停止」去猜：别的标签页、IM 或程序发起的停止，
     * 本页会把还在运行的组按完成结算（绿勾），刷新后才变成「被中断」。由后端明确告知，所有页面一致，
     * 也与回放同一口径（回放里未结束的子任务同样显示为被中断）。</p>
     */
    public List<WebEvent<?>> interruptOpenTasks() {
        List<WebEvent<?>> out = new ArrayList<>(openTasks.size());
        for (WebEvent<?> start : openTasks.values()) {
            Object payload = start.getPayload();
            TaskStartPayload sp = payload instanceof TaskStartPayload ? (TaskStartPayload) payload : null;
            WebEvent<?> done = WebEvent.of(WebEventNames.TASK_DONE, TaskDonePayload.builder()
                    .taskId(start.getTaskId())
                    .parentTaskId(sp == null ? null : sp.getParentTaskId())
                    .title(sp == null ? start.getTaskDescription() : sp.getTitle())
                    .status(TASK_STATUS_INTERRUPTED)
                    .isMultitask(sp != null && sp.isMultitask())
                    .build());
            done.setSessionId(start.getSessionId());
            done.setRunId(start.getRunId());
            done.setTaskId(start.getTaskId());
            done.setParentCallId(start.getParentCallId());
            done.setAgentName(start.getAgentName());
            done.setTaskDescription(start.getTaskDescription());
            out.add(done);
        }
        openTasks.clear();
        return out;
    }

    /** task.done 的「被中断」状态（前端 applyTaskDoneChunk 认 done / error / interrupted） */
    static final String TASK_STATUS_INTERRUPTED = "interrupted";

    /**
     * 流失败的 IM 终态（与成功终态共用去重门，并隔离旧请求的错误）。
     *
     * <p>IM 与 web 是同一 session 的输出端：流式 error 仅 web 可见，补一条非流式终态给 IM，
     * 否则 IM 端会永久停在「已读不回」。</p>
     */
    public void replyError(Throwable error) {
        builder.prepareErrorReply(context, session, imageTurn, error).run();
    }

    public ImageReplyTurn getImageTurn() {
        return imageTurn;
    }

    /** 本轮结束：释放图片回复轮次（幂等） */
    public void cleanup() {
        imageTurn.cleanup();
    }
}
