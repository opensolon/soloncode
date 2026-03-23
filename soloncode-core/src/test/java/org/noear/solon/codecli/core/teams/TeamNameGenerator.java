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
package org.noear.solon.codecli.core.teams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 团队名生成器
 *
 * 根据角色和任务目标智能生成语义化的团队名
 *
 * @author bai
 * @since 3.9.5
 */
public class TeamNameGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(TeamNameGenerator.class);

    // 领域关键词映射
    private static final java.util.Map<String, String[]> DOMAIN_KEYWORDS = new java.util.HashMap<String, String[]>() {{
        put("security", new String[]{"安全", "security", "认证", "登录", "权限", "鉴权", "加密", "防护", "漏洞", "攻击", "防御"});
        put("database", new String[]{"数据库", "database", "db", "sql", "mysql", "postgresql", "oracle", "redis", "mongodb", "存储", "查询"});
        put("frontend", new String[]{"前端", "frontend", "ui", "界面", "页面", "组件", "vue", "react", "angular", "可视化"});
        put("backend", new String[]{"后端", "backend", "api", "接口", "服务", "controller", "service", "逻辑"});
        put("devops", new String[]{"运维", "devops", "部署", "docker", "kubernetes", "k8s", "ci", "cd", "发布"});
        put("testing", new String[]{"测试", "testing", "test", "单元测试", "集成测试", "qa", "质量"});
        put("architecture", new String[]{"架构", "architecture", "设计", "模式", "分层", "微服务", "重构"});
        put("ai", new String[]{"ai", "人工智能", "机器学习", "ml", "深度学习", "模型", "算法", "智能"});
        put("performance", new String[]{"性能", "performance", "优化", "缓存", "加速", "压测", "监控"});
        put("docs", new String[]{"文档", "document", "doc", "readme", "说明", "指南"});
    }};

    // 团队名模板
    private static final String[] TEAM_NAME_TEMPLATES = {
        "{domain}-squad",           // security-squad
        "{domain}-team",            // backend-team
        "{domain}-masters",         // database-masters
        "{domain}-experts",         // frontend-experts
        "{domain}-force",           // ai-force
        "{domain}-lab",             // performance-lab
        "{domain}-guild",           // security-guild
        "{domain}-alliance",        // ops-alliance
        "the-{domain}-builders",    // the-database-builders
        "{domain}-collective"       // frontend-collective
    };

    // 默认团队名（当无法识别领域时）
    private static final String[] DEFAULT_TEAM_NAMES = {
        "innovation-team",
        "development-squad",
        "solution-builders",
        "tech-craftsmen",
        "product-force",
        "code-vanguards"
    };

    /**
     * 根据角色和任务生成团队名
     *
     * @param role 角色（如 "security-expert"）
     * @param description 描述（如 "专注于安全审计"）
     * @param taskGoal 任务目标（可选，如 "实现用户认证系统"）
     * @return 团队名
     */
    public static String generateTeamName(String role, String description, String taskGoal) {
        // 1. 从角色中提取领域
        String domain = extractDomain(role, description, taskGoal);

        if (domain != null && !domain.isEmpty()) {
            // 2. 从模板中选择
            String template = selectTemplate(domain);
            String teamName = template.replace("{domain}", domain);

            // 3. 格式化（kebab-case）
            teamName = toKebabCase(teamName);

            LOG.debug("生成团队名: role={}, description={}, domain={}, teamName={}",
                    role, description, domain, teamName);
            return teamName;
        }

        // 4. 无法识别时使用默认名
        String defaultName = DEFAULT_TEAM_NAMES[
            (int) (System.currentTimeMillis() / 1000) % DEFAULT_TEAM_NAMES.length
        ];
        LOG.debug("使用默认团队名: role={}, teamName={}", role, defaultName);
        return defaultName;
    }

    /**
     * 提取领域
     */
    private static String extractDomain(String role, String description, String taskGoal) {
        Set<String> mentionedDomains = new HashSet<>();

        String combinedText = (role + " " + description + " " + (taskGoal != null ? taskGoal : "")).toLowerCase();

        // 遍历所有领域关键词
        for (java.util.Map.Entry<String, String[]> entry : DOMAIN_KEYWORDS.entrySet()) {
            String domain = entry.getKey();
            String[] keywords = entry.getValue();

            for (String keyword : keywords) {
                if (combinedText.contains(keyword.toLowerCase())) {
                    mentionedDomains.add(domain);
                    break; // 找到一个匹配即可
                }
            }
        }

        // 返回优先级最高的领域
        if (mentionedDomains.isEmpty()) {
            return null;
        }

        // 优先级顺序（按重要性）
        String[] priority = {"security", "ai", "database", "architecture", "devops",
                             "backend", "frontend", "testing", "performance", "docs"};

        for (String domain : priority) {
            if (mentionedDomains.contains(domain)) {
                return domain;
            }
        }

        // 返回任意一个匹配的领域
        return mentionedDomains.iterator().next();
    }

    /**
     * 选择合适的模板
     */
    private static String selectTemplate(String domain) {
        // 根据领域特征选择合适的模板
        int index = (int) (domain.hashCode() % TEAM_NAME_TEMPLATES.length);
        if (index < 0) {
            index = -index;
        }
        return TEAM_NAME_TEMPLATES[index];
    }

    /**
     * 转换为 kebab-case
     */
    private static String toKebabCase(String input) {
        // 移除特殊字符，只保留字母、数字和连字符
        String cleaned = input.replaceAll("[^a-zA-Z0-9-]", "-");
        // 移除重复的连字符
        cleaned = cleaned.replaceAll("-+", "-");
        // 移除首尾连字符
        cleaned = cleaned.replaceAll("^-|-$", "");
        return cleaned.toLowerCase();
    }

    /**
     * 为现有团队生成更有意义的名称（迁移工具）
     *
     * @param oldTeamName 旧团队名（如 "team-1736640123456"）
     * @param memberNames 成员列表
     * @return 新团队名建议
     */
    public static String suggestBetterName(String oldTeamName, java.util.List<String> memberNames) {
        // 从成员角色中推断领域
        StringBuilder combinedRoles = new StringBuilder();
        for (String member : memberNames) {
            combinedRoles.append(member).append(" ");
        }

        String domain = extractDomain(combinedRoles.toString(), "", "");
        if (domain != null) {
            return domain + "-team";
        }

        return null; // 无法建议
    }

    /**
     * 解析团队名获取领域
     *
     * @param teamName 团队名（如 "security-squad"）
     * @return 领域（如 "security"）
     */
    public static String extractDomainFromTeamName(String teamName) {
        for (String domain : DOMAIN_KEYWORDS.keySet()) {
            if (teamName.toLowerCase().startsWith(domain + "-") ||
                teamName.toLowerCase().contains("-" + domain + "-")) {
                return domain;
            }
        }
        return null;
    }

    /**
     * 获取团队描述
     *
     * @param teamName 团队名
     * @return 团队描述
     */
    public static String getTeamDescription(String teamName) {
        String domain = extractDomainFromTeamName(teamName);
        if (domain == null) {
            return "通用开发团队";
        }

        switch (domain) {
            case "security":
                return "专注于系统安全、身份认证和访问控制的专家团队";
            case "database":
                return "数据库设计、优化和维护的专家团队";
            case "frontend":
                return "用户界面和前端组件开发的专业团队";
            case "backend":
                return "后端服务、API 和业务逻辑的实现团队";
            case "devops":
                return "部署、运维和基础设施管理的自动化团队";
            case "testing":
                return "质量保证和自动化测试的专业团队";
            case "architecture":
                return "系统架构设计和技术选型的决策团队";
            case "ai":
                return "人工智能和机器学习算法的实现团队";
            case "performance":
                return "性能优化、缓存和加速方案的专业团队";
            case "docs":
                return "技术文档编写和维护的支持团队";
            default:
                return "专业开发团队";
        }
    }

    /**
     * 验证团队名是否有效
     *
     * @param teamName 团队名
     * @return 是否有效
     */
    public static boolean isValidTeamName(String teamName) {
        if (teamName == null || teamName.isEmpty()) {
            return false;
        }

        // 只能包含字母、数字、连字符
        Pattern pattern = Pattern.compile("^[a-z0-9-]+$");
        Matcher matcher = pattern.matcher(teamName);
        return matcher.matches();
    }

    /**
     * 规范化团队名
     *
     * @param teamName 原始团队名
     * @return 规范化后的团队名
     */
    public static String normalizeTeamName(String teamName) {
        if (teamName == null || teamName.isEmpty()) {
            return "default-team";
        }

        // 转为小写
        teamName = teamName.toLowerCase();

        // 替换空格和下划线为连字符
        teamName = teamName.replace(" ", "-").replace("_", "-");

        // 移除特殊字符
        teamName = teamName.replaceAll("[^a-z0-9-]", "");

        // 移除重复连字符
        teamName = teamName.replaceAll("-+", "-");

        // 移除首尾连字符
        teamName = teamName.replaceAll("^-|-$", "");

        // 如果为空，返回默认
        if (teamName.isEmpty()) {
            return "default-team";
        }

        return teamName;
    }
}
