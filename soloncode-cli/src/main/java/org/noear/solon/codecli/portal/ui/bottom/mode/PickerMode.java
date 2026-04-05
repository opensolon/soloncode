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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 纯选择模式，例如 /resume。
 */
public class PickerMode implements BottomListMode {
    private final BottomListPanel<Object> panel = new BottomListPanel<Object>();

    public <T> void open(String prompt, List<BottomListPanel.Item<T>> items) {
        open(prompt, items, 0);
    }

    public <T> void open(String prompt, List<BottomListPanel.Item<T>> items, int selectedIndex) {
        List<BottomListPanel.Item<Object>> converted = new ArrayList<BottomListPanel.Item<Object>>();
        if (items != null) {
            for (BottomListPanel.Item<T> item : items) {
                converted.add(new BottomListPanel.Item<Object>(
                        item.getValue(),
                        item.getPrimary(),
                        item.getSecondary(),
                        item.getTone()));
            }
        }
        panel.open(prompt, converted, selectedIndex);
    }

    public void setSelectedIndex(int selectedIndex) {
        panel.setSelectedIndex(selectedIndex);
    }

    public Object getSelectedValue() {
        BottomListPanel.Item<Object> selected = panel.getSelectedItem();
        return selected == null ? null : selected.getValue();
    }

    public int indexOfValue(Object value) {
        return panel.indexOfValue(value);
    }

    @Override
    public boolean isActive() {
        return panel.isActive();
    }

    @Override
    public String getPrompt() {
        return panel.getPrompt();
    }

    @Override
    public List<BottomListPanel.Item<Object>> getItems() {
        return panel.isActive() ? panel.getItems() : Collections.<BottomListPanel.Item<Object>>emptyList();
    }

    @Override
    public int getSelectedIndex() {
        return panel.getSelectedIndex();
    }

    @Override
    public boolean hasItems() {
        return panel.hasItems();
    }

    @Override
    public void moveUp() {
        panel.moveUp();
    }

    @Override
    public void moveDown() {
        panel.moveDown();
    }

    @Override
    public Object confirm() {
        BottomListPanel.Item<Object> selected = panel.getSelectedItem();
        panel.close();
        return selected == null ? null : selected.getValue();
    }

    @Override
    public void cancel() {
        panel.close();
    }
}
