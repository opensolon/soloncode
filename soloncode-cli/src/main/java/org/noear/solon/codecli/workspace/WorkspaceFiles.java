/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.workspace;

/**
 * workspace 级文件根目录约定。
 *
 * <p>{@code .files} 是宿主与引擎共享的 workspace 级文件根（运行期产物，随工作区走但 gitignore）：
 * 附件落 {@code .files/uploads}，harness 子代理超长结果落 {@code .files/task}
 * （{@code org.noear.solon.ai.harness.agent.task.TaskTalent} 自持同名约定，两仓需同步改）。
 * 文件面板隐藏目录白名单按 {@link #FILES_ROOT} 入口粒度放行。</p>
 *
 * <p>纯常量、无 IO：具体落盘职责在各落盘方（Web/桌面附件、harness spill）。</p>
 *
 * @author noear 2026/11/3 created
 */
public final class WorkspaceFiles {
    /** workspace 级文件根（相对工作区根，含前导点号） */
    public static final String FILES_ROOT = ".files";

    /** 附件目录（Web/桌面上传文件的统一落点，相对工作区根） */
    public static final String UPLOADS_DIR = FILES_ROOT + "/uploads";

    private WorkspaceFiles() {
    }
}
