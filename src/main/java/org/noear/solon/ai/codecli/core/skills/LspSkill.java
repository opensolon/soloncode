package org.noear.solon.ai.codecli.core.skills;


import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.skill.AbsSkill;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;

/**
 * 模拟 LSP 诊断工具 (以 Java 编译检查为例)
 */
public class LspSkill extends AbsSkill {
    private final String workDir;

    public LspSkill(String workDir) {
        this.workDir = workDir;
    }

    @ToolMapping(name = "check_errors", description = "检查指定文件或项目的语法错误。在修改代码后应调用此工具验证。")
    public String checkErrors(String path) {
        File target = (path == null || path.isEmpty()) ? new File(workDir) : new File(workDir, path);

        // 这里以 Java 为例，如果是通用 Agent，可以根据文件后缀调用不同编译器
        if (target.getName().endsWith(".java")) {
            return runCommand("javac -Xlint:none -cp . " + target.getAbsolutePath());
        } else if (new File(workDir, "pom.xml").exists()) {
            return runCommand("mvn compile");
        }

        return "暂不支持该类型文件的自动化诊断，请通过 bash 手动验证。";
    }

    private String runCommand(String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(command, null, new File(workDir));
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();

            if (output.length() == 0) {
                return "✅ 检查通过，未发现语法错误。";
            }
            return "❌ 发现潜在错误/警告:\n" + output.toString();
        } catch (Exception e) {
            return "诊断执行失败: " + e.getMessage();
        }
    }
}