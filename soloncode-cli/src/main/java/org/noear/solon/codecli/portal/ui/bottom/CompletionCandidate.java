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

/**
 * 补全候选项
 */
public class CompletionCandidate {
    /** 显示文本 */
    private final String display;
    /** 实际插入值 */
    private final String value;
    /** 是否为目录（用于 @ 文件模式） */
    private final boolean isDirectory;
    /** 描述（用于命令模式） */
    private final String description;

    public CompletionCandidate(String display, String value, boolean isDirectory, String description) {
        this.display = display;
        this.value = value;
        this.isDirectory = isDirectory;
        this.description = description;
    }

    public String getDisplay() {
        return display;
    }

    public String getValue() {
        return value;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 获取显示文本（目录加 / 后缀）
     */
    public String getDisplayWithSuffix() {
        if (isDirectory) {
            return display + "/";
        }
        return display;
    }
}
