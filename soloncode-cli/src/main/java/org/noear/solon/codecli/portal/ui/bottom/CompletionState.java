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
package org.noear.solon.codecli.portal.ui.bottom;

import java.util.ArrayList;
import java.util.List;

/**
 * 补全状态管理
 */
public class CompletionState {
    /** 当前模式 */
    private Mode mode = Mode.NONE;
    /** 候选列表 */
    private List<CompletionCandidate> candidates = new ArrayList<>();
    /** 当前选中索引 */
    private int selectedIndex = 0;
    /** 过滤器（用户输入的前缀） */
    private String filter = "";
    /** 命令模式下的触发位置 */
    private int commandStart = -1;
    /** 文件模式下的当前目录 */
    private String currentPath = "";
    /** 文件模式下的用户输入路径 */
    private String userInputPath = "";

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public List<CompletionCandidate> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<CompletionCandidate> candidates) {
        this.candidates = candidates;
        this.selectedIndex = 0;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int selectedIndex) {
        this.selectedIndex = selectedIndex;
    }

    public void moveUp() {
        if (selectedIndex > 0) {
            selectedIndex--;
        }
    }

    public void moveDown() {
        if (selectedIndex < candidates.size() - 1) {
            selectedIndex++;
        }
    }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public int getCommandStart() {
        return commandStart;
    }

    public void setCommandStart(int commandStart) {
        this.commandStart = commandStart;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    public void setCurrentPath(String currentPath) {
        this.currentPath = currentPath;
    }

    public String getUserInputPath() {
        return userInputPath;
    }

    public void setUserInputPath(String userInputPath) {
        this.userInputPath = userInputPath;
    }

    public CompletionCandidate getSelectedCandidate() {
        if (candidates.isEmpty() || selectedIndex < 0 || selectedIndex >= candidates.size()) {
            return null;
        }
        return candidates.get(selectedIndex);
    }

    /**
     * 重置补全状态
     */
    public void reset() {
        this.mode = Mode.NONE;
        this.candidates.clear();
        this.selectedIndex = 0;
        this.filter = "";
        this.commandStart = -1;
        this.currentPath = "";
        this.userInputPath = "";
    }

    /**
     * 是否有有效候选项
     */
    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    /** 补全模式 */
    public enum Mode {
        NONE,   // 无补全
        COMMAND, // / 命令模式
        FILE    // @ 文件模式
    }
}
