package org.noear.solon.ai.codecli.core.tool;

import org.noear.solon.ai.chat.tool.AbsTool;
import org.noear.solon.ai.rag.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LoadSkillTool extends AbsTool {
    private static final Logger LOG = LoggerFactory.getLogger(LoadSkillTool.class);
    private final Path skillsPath;

    public LoadSkillTool(String skillsRootDir) {
        this.skillsPath = Paths.get(skillsRootDir).toAbsolutePath().normalize();
        addParam("name", String.class, true, "The name of the skill from available_skills" + getParamHint());
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        List<SkillModel> accessibleSkills = scanSkills();

        if (accessibleSkills.isEmpty()) {
            return "Load a specialized skill that provides domain-specific instructions and workflows. No skills are currently available.";
        }

        List<String> lines = new ArrayList<>();
        lines.add("Load a specialized skill that provides domain-specific instructions and workflows.");
        lines.add("");
        lines.add("When you recognize that a task matches one of the available skills listed below, use this tool to load the full skill instructions.");
        lines.add("");
        lines.add("The skill will inject detailed instructions, workflows, and access to bundled resources (scripts, references, templates) into the conversation context.");
        lines.add("");
        lines.add("Tool output includes a `<skill_content name=\"...\">` block with the loaded content.");
        lines.add("");
        lines.add("The following skills provide specialized sets of instructions for particular tasks");
        lines.add("Invoke this tool to load a skill when a task matches one of the available skills listed below:");
        lines.add("");
        lines.add("<available_skills>");

        for (SkillModel skill : accessibleSkills) {
            lines.add("  <skill>");
            lines.add("    <name>" + skill.name + "</name>");
            lines.add("    <description>" + skill.description + "</description>");
            // 协议对齐：file:///
            lines.add("    <location>" + toFileUrl(skill.path.resolve("SKILL.md")) + "</location>");
            lines.add("  </skill>");
        }
        lines.add("</available_skills>");

        return String.join("\n", lines);
    }

    @Override
    public Object handle(Map<String, Object> args) throws Throwable {
        String name = (String) args.get("name");

        List<SkillModel> allSkills = scanSkills();
        SkillModel skill = allSkills.stream()
                .filter(s -> s.name.equals(name))
                .findFirst()
                .orElse(null);

        // 报错文案 100% 对齐：Skill "${params.name}" not found. Available skills: ${available || "none"}
        if (skill == null) {
            String available = allSkills.stream().map(s -> s.name).collect(Collectors.joining(", "));
            throw new RuntimeException("Skill \"" + name + "\" not found. Available skills: " + (available.isEmpty() ? "none" : available));
        }

        // 模拟 ctx.ask 逻辑
        LOG.info("Checking permission for skill: {}", name);

        try {
            String base = toFileUrl(skill.path);
            Path skillFile = skill.path.resolve("SKILL.md");
            String skillContent = Files.exists(skillFile) ? new String(Files.readAllBytes(skillFile)) : "";

            // 采样逻辑 100% 对齐（排除 SKILL.md，绝对路径）
            String filesXml = sampleSkillFiles(skill.path);

            // 输出数组像素级对齐
            List<String> outputLines = new ArrayList<>();
            outputLines.add("<skill_content name=\"" + skill.name + "\">");
            outputLines.add("# Skill: " + skill.name);
            outputLines.add("");
            outputLines.add(skillContent.trim());
            outputLines.add("");
            outputLines.add("Base directory for this skill: " + base);
            outputLines.add("Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.");
            outputLines.add("Note: file list is sampled.");
            outputLines.add("");
            outputLines.add("<skill_files>");
            outputLines.add(filesXml);
            outputLines.add("</skill_files>");
            outputLines.add("</skill_content>");

            // 100% 对齐返回结构：包含 title, output(content), 以及 TS 中的 metadata
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("name", skill.name);
            metadata.put("dir", skill.path.toAbsolutePath().toString().replace("\\", "/"));

            return new Document()
                    .title("Loaded skill: " + skill.name)
                    .content(String.join("\n", outputLines))
                    .metadata(metadata); // 补全元数据对齐

        } catch (IOException e) {
            LOG.error("Failed to load skill: " + name, e);
            throw new RuntimeException("Execute load_skill failed: " + e.getMessage());
        }
    }

    private String toFileUrl(Path path) {
        String url = path.toUri().toString();
        return url.replace("file:/", "file:///").replace("////", "///");
    }

    private String getParamHint() {
        List<SkillModel> skills = scanSkills();
        if (skills.isEmpty()) return "";
        String examples = skills.stream()
                .limit(3)
                .map(s -> "'" + s.name + "'")
                .collect(Collectors.joining(", "));
        return " (e.g., " + examples + ", ...)";
    }

    private List<SkillModel> scanSkills() {
        List<SkillModel> list = new ArrayList<>();
        try {
            if (Files.exists(skillsPath)) {
                try (Stream<Path> stream = Files.list(skillsPath)) {
                    stream.filter(Files::isDirectory).forEach(p ->
                            list.add(new SkillModel(p.getFileName().toString(), p)));
                }
            }
        } catch (IOException e) {
            LOG.error("Scan error", e);
        }
        return list;
    }

    private String sampleSkillFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir, 2)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().contains("SKILL.md"))
                    .limit(10)
                    .map(p -> "<file>" + p.toAbsolutePath().toString().replace("\\", "/") + "</file>")
                    .collect(Collectors.joining("\n"));
        }
    }

    private static class SkillModel {
        String name;
        String description;
        Path path;

        SkillModel(String name, Path path) {
            this.name = name;
            this.path = path;
            this.description = "Specialized workflows for " + name;
        }
    }
}