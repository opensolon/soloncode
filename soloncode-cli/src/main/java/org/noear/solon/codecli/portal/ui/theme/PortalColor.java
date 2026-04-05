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
package org.noear.solon.codecli.portal.ui.theme;

import org.jline.utils.AttributedStyle;

/**
 * Portal UI 主题色定义。
 */
public class PortalColor {
    private final int r;
    private final int g;
    private final int b;

    public PortalColor(int r, int g, int b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }

    public static PortalColor rgb(int r, int g, int b) {
        return new PortalColor(r, g, b);
    }

    public int r() {
        return r;
    }

    public int g() {
        return g;
    }

    public int b() {
        return b;
    }

    public String ansiFg() {
        return "\033[38;2;" + r + ";" + g + ";" + b + "m";
    }

    public String ansiStyledFg(String prefix) {
        return "\033[" + prefix + ";38;2;" + r + ";" + g + ";" + b + "m";
    }

    public String ansiBoldFg() {
        return "\033[1;38;2;" + r + ";" + g + ";" + b + "m";
    }

    public String ansiDimFg() {
        return "\033[2;38;2;" + r + ";" + g + ";" + b + "m";
    }

    public AttributedStyle style() {
        return AttributedStyle.DEFAULT.foreground(r, g, b);
    }

    public AttributedStyle boldStyle() {
        return AttributedStyle.BOLD.foreground(r, g, b);
    }
}
