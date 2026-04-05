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


import org.noear.solon.codecli.portal.ui.bottom.CompletionCandidate;
import org.noear.solon.codecli.portal.ui.bottom.CompletionState;
import org.noear.solon.codecli.portal.ui.bottom.panel.BottomListPanel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * 输入驱动的补全面板模式。
 */
public abstract class CompletionMode implements BottomListMode {
    private final CompletionState state;
    private final Supplier<String> promptSupplier;

    protected CompletionMode(CompletionState state, Supplier<String> promptSupplier) {
        this.state = state;
        this.promptSupplier = promptSupplier;
    }

    protected abstract CompletionState.Mode mode();

    protected abstract BottomListPanel.Item<Object> toItem(CompletionCandidate candidate);

    @Override
    public boolean isActive() {
        return state.getMode() == mode() && state.hasCandidates();
    }

    @Override
    public String getPrompt() {
        return promptSupplier.get();
    }

    @Override
    public List<BottomListPanel.Item<Object>> getItems() {
        if (!isActive()) {
            return Collections.<BottomListPanel.Item<Object>>emptyList();
        }

        List<BottomListPanel.Item<Object>> items = new ArrayList<BottomListPanel.Item<Object>>();
        for (CompletionCandidate candidate : state.getCandidates()) {
            items.add(toItem(candidate));
        }
        return items;
    }

    @Override
    public int getSelectedIndex() {
        return state.getSelectedIndex();
    }

    @Override
    public boolean hasItems() {
        return isActive();
    }

    @Override
    public void moveUp() {
        state.moveUp();
    }

    @Override
    public void moveDown() {
        state.moveDown();
    }

    @Override
    public Object confirm() {
        return state.getSelectedCandidate();
    }

    @Override
    public void cancel() {
        state.reset();
    }
}
