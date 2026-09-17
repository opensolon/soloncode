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
package org.noear.solon.codecli.util;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 目录选择子进程主类：在以 {@code -Djava.awt.headless=false} 启动的子 JVM 中运行，
 * 弹 {@code JFileChooser(DIRECTORIES_ONLY)} 并把结果按协议输出到 stdout。
 * <p>
 * 协议（见 {@link DirectoryPickerUtil}）：第一行 {@code PICK <abs-path>} 或 {@code PICK_NONE}；
 * 弹框失败（无显示器等）以非 0 退出码结束，错误信息走 stderr。
 * <p>
 * <b>必须保持零第三方依赖</b>：fat jar 模式下本类会被单独抽取到临时目录运行，
 * 宿主 classpath 上的任何依赖（含 Solon）都不可用，只能用 JDK 自带 API。
 * <p>
 * 启动参数（全部可选，按位传）：
 * <ol>
 *     <li>title：对话框标题（空串表示无）</li>
 *     <li>startDir：起始目录绝对路径（空串表示回落 user.home）</li>
 * </ol>
 *
 * @author noear
 * @since 3.9.1
 */
public final class DirectoryPickerSubprocess {
    private DirectoryPickerSubprocess() {
    }

    public static void main(String[] args) {
        String title = args.length > 0 ? args[0] : "";
        File startDir = args.length > 1 && !args[1].isEmpty() ? new File(args[1]) : null;

        int exit;
        try {
            exit = run(title, startDir);
        } catch (Throwable t) {
            // 失败信息走 stderr（宿主已 redirectErrorStream，会并入诊断输出）
            t.printStackTrace();
            exit = 1;
        }
        System.exit(exit);
    }

    private static int run(String title, File startDir) throws Exception {
        PrintWriter protocol = new PrintWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Throwable ignored) {
            // 保持默认 LAF，不影响功能
        }

        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        if (title != null && !title.isEmpty()) {
            chooser.setDialogTitle(title);
        }
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setMultiSelectionEnabled(false);

        if (startDir != null && startDir.isDirectory()) {
            chooser.setCurrentDirectory(startDir);
        }

        int rc = chooser.showOpenDialog(null);
        if (rc == javax.swing.JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            protocol.println("PICK " + chooser.getSelectedFile().getAbsolutePath());
            return 0;
        }
        protocol.println("PICK_NONE");
        return 0;
    }
}
