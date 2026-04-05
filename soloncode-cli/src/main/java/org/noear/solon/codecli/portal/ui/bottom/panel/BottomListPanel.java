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
package org.noear.solon.codecli.portal.ui.bottom.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可复用的底部单选面板模型。
 */
public class BottomListPanel<T> {
    public enum Tone {
        TEXT,
        SOFT,
        ACCENT
    }

    public static final class Item<T> {
        private final T value;
        private final String primary;
        private final String secondary;
        private final Tone tone;

        public Item(T value, String primary, String secondary, Tone tone) {
            this.value = value;
            this.primary = primary == null ? "" : primary;
            this.secondary = secondary == null ? "" : secondary;
            this.tone = tone == null ? Tone.TEXT : tone;
        }

        public T getValue() {
            return value;
        }

        public String getPrimary() {
            return primary;
        }

        public String getSecondary() {
            return secondary;
        }

        public Tone getTone() {
            return tone;
        }
    }

    private String prompt = "";
    private List<Item<T>> items = Collections.emptyList();
    private int selectedIndex = 0;
    private boolean active = false;

    public void open(String prompt, List<Item<T>> items) {
        open(prompt, items, 0);
    }

    public void open(String prompt, List<Item<T>> items, int selectedIndex) {
        this.prompt = prompt == null ? "" : prompt;
        this.items = items == null ? Collections.<Item<T>>emptyList() : new ArrayList<Item<T>>(items);
        this.selectedIndex = this.items.isEmpty() ? -1 : 0;
        setSelectedIndex(selectedIndex);
        this.active = !this.items.isEmpty();
    }

    public void close() {
        this.prompt = "";
        this.items = Collections.emptyList();
        this.selectedIndex = 0;
        this.active = false;
    }

    public boolean isActive() {
        return active;
    }

    public String getPrompt() {
        return prompt;
    }

    public List<Item<T>> getItems() {
        return items;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int selectedIndex) {
        if (items.isEmpty()) {
            this.selectedIndex = -1;
            return;
        }
        if (selectedIndex < 0) {
            this.selectedIndex = 0;
            return;
        }
        this.selectedIndex = Math.min(selectedIndex, items.size() - 1);
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }

    public void moveUp() {
        if (selectedIndex > 0) {
            selectedIndex--;
        }
    }

    public void moveDown() {
        if (selectedIndex >= 0 && selectedIndex < items.size() - 1) {
            selectedIndex++;
        }
    }

    public Item<T> getSelectedItem() {
        if (selectedIndex < 0 || selectedIndex >= items.size()) {
            return null;
        }
        return items.get(selectedIndex);
    }

    public int indexOfValue(T value) {
        if (items.isEmpty()) {
            return -1;
        }

        for (int i = 0; i < items.size(); i++) {
            Item<T> item = items.get(i);
            if (value == null ? item.getValue() == null : value.equals(item.getValue())) {
                return i;
            }
        }

        return -1;
    }
}
