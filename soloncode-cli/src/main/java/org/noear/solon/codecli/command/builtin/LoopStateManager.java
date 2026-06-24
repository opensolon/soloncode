/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.command.builtin;

import org.noear.solon.codecli.config.AgentFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;

/**
 * Loop 状态管理器 — 负责 .soloncode/loops/&lt;loopId&gt;/ 目录的创建、清理。
 *
 * @author noear
 * @since 3.9.1
 */
public class LoopStateManager {
    private static final Logger LOG = LoggerFactory.getLogger(LoopStateManager.class);

    /**
     * 获取 loop 状态目录的根路径（.soloncode/loops/）
     */
    public static Path getLoopBaseDir(String workspace) {
        return Paths.get(workspace, AgentFlags.getHarnessLoops());
    }

    /**
     * 获取指定任务的状态目录路径
     */
    public static Path getStateDir(String workspace, String loopId) {
        return Paths.get(workspace, AgentFlags.getHarnessLoops(), loopId);
    }

    /**
     * 初始化状态目录
     *
     * @return 状态目录路径
     */
    public static String init(String workspace, String loopId, String prompt) {
        Path stateDir = getStateDir(workspace, loopId);
        try {
            Files.createDirectories(stateDir);

            return stateDir.toString();
        } catch (Exception e) {
            LOG.warn("Failed to init loop state dir '{}': {}", stateDir, e.getMessage());
            return stateDir.toString();
        }
    }

    /**
     * 清理状态目录
     */
    public static void cleanup(String workspace, String loopId) {
        try {
            Path stateDir = getStateDir(workspace, loopId);
            if (Files.exists(stateDir)) {
                Files.walk(stateDir)
                        .sorted((a, b) -> b.compareTo(a)) // 先删文件再删目录
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
            }
        } catch (Exception e) {
            LOG.warn("Failed to cleanup loop state '{}': {}", loopId, e.getMessage());
        }
    }
}
