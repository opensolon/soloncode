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
package org.noear.solon.codecli.portal.ui.bottom.mode;

import org.noear.solon.codecli.portal.ui.bottom.panel.BottomListPanel;

import java.util.List;

/**
 * 底部面板模式：统一面板渲染，差异留给具体模式处理。
 */
public interface BottomListMode {
    boolean isActive();

    String getPrompt();

    List<BottomListPanel.Item<Object>> getItems();

    int getSelectedIndex();

    boolean hasItems();

    void moveUp();

    void moveDown();

    Object confirm();

    void cancel();
}
