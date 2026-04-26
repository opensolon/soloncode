# Soloncode CLI 自定义命令示例

本文档包含多个自定义命令（Custom Command）示例，兼容 Claude Code 的 Markdown Command 规范。

---

## 目录

- [基础示例：Git Commit](#1-git-commit)
- [带位置参数：代码审查](#2-代码审查)
- [带工具限制：安全部署](#3-安全部署)
- [无 Frontmatter：简单命令](#4-简单命令)
- [子目录命名空间：CI 命令组](#5-ci-命令组)

---

## 1. Git Commit

文件路径：`.soloncode/commands/commit.md`

```markdown
---
description: Generate a git commit with staged changes
argument-hint: [commit message]
allowed-tools: Bash(git add:*), Bash(git status:*), Bash(git diff:*), Bash(git commit:*)
---

Analyze the staged changes and create a git commit.

Requirements:
- Write a clear, concise commit message in conventional commit format
- Use the provided message as reference: $ARGUMENTS
- If no message is provided, generate one based on the diff
- Do not push the commit

Steps:
1. Run `git status` to see what's staged
2. Run `git diff --cached` to review staged changes
3. Run `git commit -m "<message>"` with the generated commit message
```

使用方式：
```
> /commit fix login timeout bug
> /commit
```

---

## 2. 代码审查

文件路径：`.soloncode/commands/review.md`

```markdown
---
description: Review code changes in current branch
argument-hint: <target-branch>
---

Review the code changes in the current branch compared to $1 (default: main).

Review checklist:
1. **Correctness**: Are there logic errors or edge cases?
2. **Security**: Any injection risks, exposed secrets, or auth issues?
3. **Performance**: Any obvious bottlenecks or unnecessary operations?
4. **Readability**: Is the code clear and well-documented?
5. **Testing**: Are critical paths covered by tests?

For each issue found, provide:
- File and line reference
- Severity (critical / warning / suggestion)
- Suggested fix
```

使用方式：
```
> /review main
> /review develop
```

---

## 3. 安全部署

文件路径：`.soloncode/commands/deploy.md`

```markdown
---
description: Deploy project to target environment
argument-hint: <environment>
allowed-tools: Bash(mvn:*)
---

Deploy the project to the specified environment: $ARGUMENTS

Steps:
1. Confirm the target environment is $1
2. Run `mvn clean package -DskipTests` to build the project
3. Check the build output for errors
4. Run `mvn deploy -Denv=$1`
5. Verify the deployment is successful

Important:
- Only Maven commands are allowed for safety
- Do NOT modify any source files during deployment
- Always confirm before executing the deploy command
```

使用方式：
```
> /deploy staging
> /deploy production
```

---

## 4. 简单命令

文件路径：`.soloncode/commands/explain.md`

（无 Frontmatter 的向后兼容格式，使用 HTML 注释作为描述）

```markdown
<!-- Explain the codebase architecture -->
Explain the overall architecture of this project:

1. List the main modules and their responsibilities
2. Describe the dependency relationships between modules
3. Identify the entry points and key configuration files
4. Summarize the tech stack and frameworks used

Use $ARGUMENTS as additional context for the explanation.
```

使用方式：
```
> /explain
> /explain focus on the data layer
```

---

## 5. CI 命令组

利用子目录命名空间，可以将命令按功能分组。

### 5.1 CI 构建

文件路径：`.soloncode/commands/ci/build.md`

注册为命令：`/ci:build`

```markdown
---
description: Run CI build pipeline locally
allowed-tools: Bash(mvn:*), Bash(docker:*)
---

Run the local CI build pipeline:

1. Run `mvn clean compile` to compile
2. Run `mvn test` to execute unit tests
3. Run `mvn package -DskipTests` to package
4. Report the build result summary

Additional options: $ARGUMENTS
```

### 5.2 CI 测试

文件路径：`.soloncode/commands/ci/test.md`

注册为命令：`/ci:test`

```markdown
---
description: Run tests with optional filters
argument-hint: [test class or pattern]
allowed-tools: Bash(mvn:*)
---

Run the test suite.

If $ARGUMENTS is provided, run only the matching tests.
Otherwise, run all tests.

Steps:
1. If argument provided: `mvn test -Dtest=$ARGUMENTS`
2. If no argument: `mvn test`
3. Summarize test results: passed, failed, skipped
4. Highlight any failures with details
```

### 5.3 Docker 构建

文件路径：`.soloncode/commands/ci/docker.md`

注册为命令：`/ci:docker`

```markdown
---
description: Build and tag Docker image
argument-hint: <tag>
allowed-tools: Bash(docker:*)
---

Build a Docker image for this project.

Tag: $1 (required)
Additional args: $ARGUMENTS

Steps:
1. Check for Dockerfile in project root
2. Run `docker build -t $1 .`
3. Verify the image was created: `docker images $1`
4. Report image size and tag
```

---

## 命令文件放置位置

| 位置 | 路径 | 作用域 |
|------|------|--------|
| 项目级 | `.soloncode/commands/*.md` | 仅当前项目 |
| 用户级 | `~/.soloncode/commands/*.md` | 所有项目 |

## Frontmatter 字段说明

| 字段 | 必填 | 说明 |
|------|------|------|
| `description` | 否 | 命令描述，显示在 `/help` 列表中 |
| `argument-hint` | 否 | 参数提示，显示在 Tab 补全中，如 `[message]` 或 `<env>` |
| `allowed-tools` | 否 | 限制命令可使用的工具列表，如 `Bash(git add:*)` |

## 变量说明

| 变量 | 说明 | 示例 |
|------|------|------|
| `$ARGUMENTS` | 所有参数拼接为单个字符串 | `/commit fix bug` → `fix bug` |
| `$1`, `$2`... | 按位置取单个参数 | `/deploy staging` → `$1` = `staging` |
