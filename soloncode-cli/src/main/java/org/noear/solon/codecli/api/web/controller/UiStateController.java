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
package org.noear.solon.codecli.api.web.controller;

import org.noear.snack4.ONode;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.api.web.AbstractWebController;
import org.noear.solon.codecli.api.web.service.UiStateStore;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Result;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web UI 状态（后端记忆）HTTP 入口。
 *
 * <p>为什么需要：前端的 localStorage 按 origin（含端口）隔离，而 Web 支持随机端口启动，
 * 重启即换 origin，导致「上次的对话 / 主题 / 布局」全部丢失（Gitee #IKJOCR）。
 * 这里把这类轻量界面状态存到服务端（{@link UiStateStore}，落
 * {@code ~/.soloncode/ui-state.json}），端口变化不再影响。</p>
 *
 * <ul>
 *   <li>{@code GET  /web/ui/state}       —— 拉取当前用户作用域的全部键值（前端启动时水合）</li>
 *   <li>{@code POST /web/ui/state/save}  —— 增量合并保存（{@code {"key":"value"}}；值为 null 表示删除）</li>
 * </ul>
 *
 * <p>作用域键取当前登录用户（启用「对话隔离」时按用户分片，否则共享
 * {@link UiStateStore#SCOPE_DEFAULT}），与会话按 ownerUserId 过滤的口径一致。</p>
 *
 * @author noear 2026/10
 */
public class UiStateController extends AbstractWebController {

    public UiStateController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    /**
     * 读取当前作用域的界面状态。
     */
    @Get
    @Mapping("/web/ui/state")
    public Result<Map<String, String>> stateGet() {
        return Result.succeed(store().load(scopeKey()));
    }

    /**
     * 增量保存界面状态；返回合并后的全量键值，便于前端校准本地缓存。
     *
     * <p>请求体形如 {@code {"chat-theme":"dark","soloncode-active-session":"web-xxx"}}，
     * 值为 {@code null} 表示删除该键。非法键/超长值由存储层忽略。</p>
     */
    @Post
    @Mapping("/web/ui/state/save")
    public Result<Map<String, String>> stateSave(@Body String json) {
        Map<String, String> changes = new LinkedHashMap<>();
        try {
            ONode root = ONode.ofJson(json);
            if (root == null || root.isObject() == false) {
                return Result.failure("无效的界面状态数据");
            }

            for (Map.Entry<String, ONode> entry : root.getObject().entrySet()) {
                ONode node = entry.getValue();
                if (node == null || node.isNull()) {
                    changes.put(entry.getKey(), null);
                } else if (node.isObject() == false && node.isArray() == false) {
                    changes.put(entry.getKey(), node.getString());
                }
            }
        } catch (Exception e) {
            return Result.failure("无效的界面状态数据: " + e.getMessage());
        }

        try {
            return Result.succeed(store().merge(scopeKey(), changes));
        } catch (IllegalStateException e) {
            return Result.failure("保存界面状态失败: " + e.getMessage());
        }
    }

    /**
     * 当前作用域键：登录用户（未启用对话隔离时为共享作用域）。
     */
    private String scopeKey() {
        return UiStateStore.normalizeScope(getCurrentUserId());
    }

    private UiStateStore store() {
        return new UiStateStore(UiStateStore.storePath(
                engine().getUserHome(), engine().getHarnessHome()));
    }
}
