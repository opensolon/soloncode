use serde::{Deserialize, Serialize};
use std::fs;
use std::io::{Read as IoRead, Write as IoWrite};
use std::path::Path;
use std::process::{Child, Command};
use std::sync::Mutex;
use tauri::{Manager, Emitter};
use portable_pty::{native_pty_system, PtySize, CommandBuilder as PtyCommandBuilder};

#[derive(Debug, Serialize, Deserialize)]
pub struct FileInfo {
    name: String,
    path: String,
    is_dir: bool,
    children: Option<Vec<FileInfo>>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct WorkspaceInfo {
    path: String,
    name: String,
}

// ==================== Git 相关结构体 ====================

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct GitFileStatus {
    path: String,
    status: String,  // "modified", "added", "deleted", "untracked"
    staged: bool,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct GitStatusResult {
    branch: String,
    ahead: u32,
    behind: u32,
    files: Vec<GitFileStatus>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct GitLogEntry {
    hash: String,
    short_hash: String,
    author: String,
    date: String,
    message: String,
}

/// 执行 git 命令的辅助函数
fn run_git(args: &[&str], cwd: &str) -> Result<String, String> {
    let output = Command::new("git")
        .args(args)
        .current_dir(cwd)
        .output()
        .map_err(|e| format!("执行 git 命令失败: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("git {} 失败: {}", args.join(" "), stderr.trim()));
    }

    Ok(String::from_utf8_lossy(&output.stdout).to_string())
}

/// 读取文件内容
#[tauri::command]
fn read_file(path: &str) -> Result<String, String> {
    fs::read_to_string(path).map_err(|e| format!("读取文件失败: {}", e))
}

/// 写入文件内容
#[tauri::command]
fn write_file(path: &str, content: &str) -> Result<(), String> {
    fs::write(path, content).map_err(|e| format!("写入文件失败: {}", e))
}

/// 列出目录内容
#[tauri::command]
fn list_directory(path: &str) -> Result<Vec<FileInfo>, String> {
    let entries = fs::read_dir(path).map_err(|e| format!("读取目录失败: {}", e))?;

    let mut files = Vec::new();
    for entry in entries {
        if let Ok(entry) = entry {
            let path_buf = entry.path();
            let is_dir = path_buf.is_dir();
            let name = path_buf
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();

            files.push(FileInfo {
                name,
                path: path_buf.to_string_lossy().to_string(),
                is_dir,
                children: None,
            });
        }
    }

    // 排序：文件夹优先，然后按名称排序
    files.sort_by(|a, b| {
        if a.is_dir == b.is_dir {
            a.name.to_lowercase().cmp(&b.name.to_lowercase())
        } else if a.is_dir {
            std::cmp::Ordering::Less
        } else {
            std::cmp::Ordering::Greater
        }
    });

    Ok(files)
}

/// 递归列出目录树
#[tauri::command]
fn list_directory_tree(path: &str, max_depth: usize) -> Result<Vec<FileInfo>, String> {
    fn build_tree(path: &Path, current_depth: usize, max_depth: usize) -> Option<Vec<FileInfo>> {
        if current_depth > max_depth {
            return None;
        }

        let entries = match fs::read_dir(path) {
            Ok(e) => e,
            Err(_) => return None,
        };

        let mut files = Vec::new();
        for entry in entries.flatten() {
            let path_buf = entry.path();
            let is_dir = path_buf.is_dir();
            let name = path_buf
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();

            // 跳过常见忽略目录
            if name == "node_modules" || name == "target" {
                continue;
            }

            let children = if is_dir && current_depth < max_depth {
                build_tree(&path_buf, current_depth + 1, max_depth)
            } else {
                None
            };

            files.push(FileInfo {
                name,
                path: path_buf.to_string_lossy().to_string(),
                is_dir,
                children,
            });
        }

        // 排序：文件夹优先
        files.sort_by(|a, b| {
            if a.is_dir == b.is_dir {
                a.name.to_lowercase().cmp(&b.name.to_lowercase())
            } else if a.is_dir {
                std::cmp::Ordering::Less
            } else {
                std::cmp::Ordering::Greater
            }
        });

        Some(files)
    }

    let path = Path::new(path);
    build_tree(path, 0, max_depth).ok_or_else(|| "无法读取目录".to_string())
}

/// 创建新文件
#[tauri::command]
fn create_file(path: &str) -> Result<(), String> {
    // 确保父目录存在
    if let Some(parent) = Path::new(path).parent() {
        if !parent.exists() {
            fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {}", e))?;
        }
    }
    fs::write(path, "").map_err(|e| format!("创建文件失败: {}", e))
}

/// 创建新目录
#[tauri::command]
fn create_directory(path: &str) -> Result<(), String> {
    fs::create_dir_all(path).map_err(|e| format!("创建目录失败: {}", e))
}

/// 删除文件
#[tauri::command]
fn delete_file(path: &str) -> Result<(), String> {
    fs::remove_file(path).map_err(|e| format!("删除文件失败: {}", e))
}

/// 删除目录
#[tauri::command]
fn delete_directory(path: &str) -> Result<(), String> {
    fs::remove_dir_all(path).map_err(|e| format!("删除目录失败: {}", e))
}

/// 重命名文件或目录
#[tauri::command]
fn rename_item(old_path: &str, new_path: &str) -> Result<(), String> {
    fs::rename(old_path, new_path).map_err(|e| format!("重命名失败: {}", e))
}

/// 检查路径是否存在
#[tauri::command]
fn path_exists(path: &str) -> bool {
    Path::new(path).exists()
}

/// 获取工作区信息
#[tauri::command]
fn get_workspace_info(path: &str) -> Result<WorkspaceInfo, String> {
    let path_buf = Path::new(path);
    if !path_buf.exists() {
        return Err("路径不存在".to_string());
    }

    let name = path_buf
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "工作区".to_string());

    Ok(WorkspaceInfo {
        path: path_buf.to_string_lossy().to_string(),
        name,
    })
}

/// 初始化工作区配置
#[tauri::command]
fn init_workspace_config(workspace_path: &str) -> Result<String, String> {
    let workspace = Path::new(workspace_path);
    if !workspace.exists() {
        return Err("工作区路径不存在".to_string());
    }

    // 创建 .soloncode 目录
    let soloncode_dir = workspace.join(".soloncode");
    if !soloncode_dir.exists() {
        fs::create_dir_all(&soloncode_dir)
            .map_err(|e| format!("创建 .soloncode 目录失败: {}", e))?;
    }

    // 创建 settings.json 文件（如果不存在）
    let settings_file = soloncode_dir.join("settings.json");
    if !settings_file.exists() {
        let default_settings = r#"{
  "version": "1.0.0",
  "project": {
    "name": "",
    "description": ""
  },
  "ai": {
    "model": "glm-4.7",
    "maxSteps": 30
  },
  "editor": {
    "fontSize": 14,
    "tabSize": 2,
    "autoSave": true
  }
}"#;
        fs::write(&settings_file, default_settings)
            .map_err(|e| format!("创建 settings.json 失败: {}", e))?;
    }

    Ok(settings_file.to_string_lossy().to_string())
}

// ==================== Git 命令 ====================

/// 获取 Git 状态
#[tauri::command]
fn git_status(cwd: &str) -> Result<GitStatusResult, String> {
    // 获取分支和 ahead/behind 信息
    let branch_output = run_git(&["status", "--porcelain=v2", "--branch"], cwd)?;

    let mut branch = String::from("HEAD");
    let mut ahead: u32 = 0;
    let mut behind: u32 = 0;
    let mut staged_files: std::collections::HashMap<String, GitFileStatus> = std::collections::HashMap::new();
    let mut unstaged_files: std::collections::HashMap<String, GitFileStatus> = std::collections::HashMap::new();
    let mut untracked_files: Vec<GitFileStatus> = Vec::new();

    for line in branch_output.lines() {
        if line.starts_with("# branch.head ") {
            let val = line.trim_start_matches("# branch.head ").trim();
            if val != "(detached)" {
                branch = val.to_string();
            }
        } else if line.starts_with("# branch.ab ") {
            let ab = line.trim_start_matches("# branch.ab ").trim();
            let parts: Vec<&str> = ab.split_whitespace().collect();
            if parts.len() >= 2 {
                ahead = parts[0].trim_start_matches('+').parse::<i32>().unwrap_or(0).max(0) as u32;
                behind = parts[1].trim_start_matches('-').parse::<i32>().unwrap_or(0).max(0) as u32;
            }
        } else if line.starts_with("1 ") {
            // 已跟踪文件的变更
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 9 {
                let xy = parts[1];
                let file_path = parts[8..].join(" ");

                // x = 暂存区状态, y = 工作区状态
                let x = xy.chars().next().unwrap_or('.');
                let y = xy.chars().nth(1).unwrap_or('.');

                // 暂存区变更
                if x != '.' && x != '?' {
                    let status = match x {
                        'A' => "added",
                        'D' => "deleted",
                        'M' | 'R' | 'C' => "modified",
                        _ => "modified",
                    };
                    staged_files.insert(file_path.clone(), GitFileStatus {
                        path: file_path.clone(),
                        status: status.to_string(),
                        staged: true,
                    });
                }

                // 工作区变更
                if y != '.' && y != '?' {
                    let status = match y {
                        'D' => "deleted",
                        'M' => "modified",
                        _ => "modified",
                    };
                    unstaged_files.insert(file_path.clone(), GitFileStatus {
                        path: file_path.clone(),
                        status: status.to_string(),
                        staged: false,
                    });
                }
            }
        } else if line.starts_with("? ") {
            // 未跟踪文件
            let file_path = line.trim_start_matches("? ").trim().to_string();
            untracked_files.push(GitFileStatus {
                path: file_path.clone(),
                status: "untracked".to_string(),
                staged: false,
            });
        }
    }

    // 合并文件列表（去重：暂存优先）
    let mut files: Vec<GitFileStatus> = Vec::new();
    for (_, f) in staged_files.iter() {
        files.push(f.clone());
    }
    for (path, f) in unstaged_files {
        if !staged_files.contains_key(&path) {
            files.push(f);
        }
    }
    files.extend(untracked_files);

    // 排序：暂存 > 已修改 > 未跟踪
    files.sort_by(|a, b| {
        let order = |f: &GitFileStatus| match (f.staged, f.status.as_str()) {
            (true, _) => 0,
            (false, "untracked") => 2,
            _ => 1,
        };
        order(a).cmp(&order(b))
    });

    Ok(GitStatusResult {
        branch,
        ahead,
        behind,
        files,
    })
}

/// 暂存文件
#[tauri::command]
fn git_add(cwd: &str, paths: Vec<String>) -> Result<(), String> {
    let args: Vec<&str> = vec!["add", "--"]
        .into_iter()
        .chain(paths.iter().map(|s| s.as_str()))
        .collect();
    run_git(&args, cwd)?;
    Ok(())
}

/// 取消暂存文件
#[tauri::command]
fn git_reset(cwd: &str, paths: Vec<String>) -> Result<(), String> {
    let args: Vec<&str> = vec!["reset", "HEAD", "--"]
        .into_iter()
        .chain(paths.iter().map(|s| s.as_str()))
        .collect();
    run_git(&args, cwd)?;
    Ok(())
}

/// 提交更改
#[tauri::command]
fn git_commit(cwd: &str, message: &str) -> Result<(), String> {
    run_git(&["commit", "-m", message], cwd)?;
    Ok(())
}

/// 推送到远程
#[tauri::command]
fn git_push(cwd: &str) -> Result<(), String> {
    run_git(&["push"], cwd)?;
    Ok(())
}

/// 拉取远程
#[tauri::command]
fn git_pull(cwd: &str) -> Result<(), String> {
    run_git(&["pull"], cwd)?;
    Ok(())
}

/// 获取提交历史
#[tauri::command]
fn git_log(cwd: &str, count: usize) -> Result<Vec<GitLogEntry>, String> {
    let count_str = count.to_string();
    let output = run_git(
        &["log", &count_str, "--pretty=format:%H%n%h%n%an%n%ai%n%s%n---END---"],
        cwd,
    )?;

    let mut entries = Vec::new();
    for block in output.split("---END---") {
        let lines: Vec<&str> = block.lines().collect();
        if lines.len() >= 5 {
            entries.push(GitLogEntry {
                hash: lines[0].trim().to_string(),
                short_hash: lines[1].trim().to_string(),
                author: lines[2].trim().to_string(),
                date: lines[3].trim().to_string(),
                message: lines[4].trim().to_string(),
            });
        }
    }

    Ok(entries)
}

/// 获取分支列表
#[tauri::command]
fn git_branches(cwd: &str) -> Result<Vec<String>, String> {
    let output = run_git(&["branch", "--list"], cwd)?;
    let branches: Vec<String> = output
        .lines()
        .map(|l| l.trim_start_matches('*').trim().to_string())
        .filter(|l| !l.is_empty())
        .collect();
    Ok(branches)
}

/// 切换分支
#[tauri::command]
fn git_checkout(cwd: &str, branch: &str) -> Result<(), String> {
    run_git(&["checkout", branch], cwd)?;
    Ok(())
}

/// 丢弃文件更改
#[tauri::command]
fn git_discard(cwd: &str, paths: Vec<String>) -> Result<(), String> {
    for path in &paths {
        let full_path = Path::new(cwd).join(path);
        if full_path.exists() {
            // 已跟踪文件的修改：git checkout -- <file>
            run_git(&["checkout", "--", path], cwd)?;
        }
        // 注意：未跟踪文件无法通过 git checkout 恢复，需要 git clean
        // 但为了安全，暂不自动删除未跟踪文件
    }
    Ok(())
}

// ==================== Git Diff 相关 ====================

/// Diff 行变更信息
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct DiffLine {
    line: u32,          // 文件中的行号（1-based）
    r#type: String,     // "added" | "modified" | "deleted"
}

/// 获取单个文件的 git diff（与 HEAD 比较）
/// 返回行级变更列表
#[tauri::command]
fn git_diff_file(cwd: &str, file_path: &str) -> Result<Vec<DiffLine>, String> {
    // 先尝试与 HEAD 的 diff（已提交后修改）
    let output = match run_git(&["diff", "HEAD", "--", file_path], cwd) {
        Ok(o) => o,
        Err(_) => {
            // 可能没有 HEAD（新仓库），尝试与暂存区比较
            match run_git(&["diff", "--", file_path], cwd) {
                Ok(o) => o,
                Err(_) => return Ok(Vec::new()),
            }
        }
    };

    if output.trim().is_empty() {
        return Ok(Vec::new());
    }

    let mut diff_lines = Vec::new();
    let mut new_line = 0u32;

    for line in output.lines() {
        // 解析 @@ -a,b +c,d @@ 格式的 hunk header
        if line.starts_with("@@") {
            if let Some(pos) = line.find('+') {
                let rest = &line[pos + 1..];
                let end = rest.find(|c: char| c == ' ' || c == ',').unwrap_or(rest.len());
                if let Ok(n) = rest[..end].parse::<u32>() {
                    new_line = n;
                }
            }
            continue;
        }

        // 新文件中的行
        if line.starts_with('+') && !line.starts_with("+++") {
            diff_lines.push(DiffLine {
                line: new_line,
                r#type: "added".to_string(),
            });
            new_line += 1;
        } else if line.starts_with('-') && !line.starts_with("---") {
            // 删除的行，记录在当前位置（用 deleted 标记）
            diff_lines.push(DiffLine {
                line: new_line,
                r#type: "deleted".to_string(),
            });
            // new_line 不增加（删除行不占新文件行号）
        } else {
            new_line += 1;
        }
    }

    Ok(diff_lines)
}

/// 递归复制目录
fn copy_dir_recursive(src: &Path, dst: &Path) -> Result<(), String> {
    fs::create_dir_all(dst).map_err(|e| format!("创建目录失败: {}", e))?;
    for entry in fs::read_dir(src).map_err(|e| format!("读取目录失败: {}", e))? {
        let entry = entry.map_err(|e| format!("读取条目失败: {}", e))?;
        let src_path = entry.path();
        let dst_path = dst.join(entry.file_name());
        if src_path.is_dir() {
            copy_dir_recursive(&src_path, &dst_path)?;
        } else {
            fs::copy(&src_path, &dst_path).map_err(|e| format!("复制文件失败: {}", e))?;
        }
    }
    Ok(())
}

/// 复制文件或目录
#[tauri::command]
fn copy_item(source_path: &str, dest_path: &str) -> Result<(), String> {
    let src = Path::new(source_path);
    if !src.exists() {
        return Err("源路径不存在".to_string());
    }
    if src.is_dir() {
        copy_dir_recursive(src, Path::new(dest_path))
    } else {
        fs::copy(src, dest_path).map(|_| ()).map_err(|e| format!("复制文件失败: {}", e))
    }
}

/// 移动文件或目录
#[tauri::command]
fn move_item(source_path: &str, dest_path: &str) -> Result<(), String> {
    fs::rename(source_path, dest_path).map_err(|e| format!("移动失败: {}", e))
}

// ==================== 终端 (PTY) ====================

use portable_pty::MasterPty;

struct PtyState {
    master: Box<dyn MasterPty + Send>,
    writer: std::sync::Mutex<Box<dyn IoWrite + Send>>,
    _child: Box<dyn portable_pty::Child + Send + 'static>,
}

static PTY_STATE: Mutex<Option<PtyState>> = Mutex::new(None);

/// 启动终端（PowerShell）
#[tauri::command]
fn terminal_start(app_handle: tauri::AppHandle, rows: u16, cols: u16, cwd: Option<String>) -> Result<(), String> {
    // 先关闭已有终端
    {
        let mut pty = PTY_STATE.lock().map_err(|e| format!("锁错误: {}", e))?;
        if pty.is_some() {
            *pty = None;
        }
    }

    let pty_system = native_pty_system();

    let pair = pty_system
        .openpty(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })
        .map_err(|e| format!("创建 PTY 失败: {}", e))?;

    // 根据操作系统选择默认 shell
    let shell: String = if cfg!(target_os = "windows") {
        "powershell".to_string()
    } else {
        // Mac/Linux 使用环境变量中的 shell，或默认 bash
        std::env::var("SHELL").unwrap_or_else(|_| "bash".to_string())
    };
    let mut cmd = PtyCommandBuilder::new(&shell);
    if let Some(dir) = cwd {
        cmd.cwd(dir);
    }

    let child = pair
        .slave
        .spawn_command(cmd)
        .map_err(|e| format!("启动终端失败: {}", e))?;

    let reader = pair
        .master
        .try_clone_reader()
        .map_err(|e| format!("获取 PTY reader 失败: {}", e))?;

    // take_writer 需要在 master 被装箱为 trait object 之前调用
    let writer = pair
        .master
        .take_writer()
        .map_err(|e| format!("获取 PTY writer 失败: {}", e))?;

    let master = pair.master;

    // 保存 PTY 状态
    {
        let mut pty = PTY_STATE.lock().map_err(|e| format!("锁错误: {}", e))?;
        *pty = Some(PtyState {
            master,
            writer: std::sync::Mutex::new(writer),
            _child: child,
        });
    }

    // 在独立线程中读取 PTY 输出并通过 Tauri 事件发送到前端
    std::thread::spawn(move || {
        let mut reader = reader;
        let mut buf = [0u8; 4096];
        loop {
            match reader.read(&mut buf) {
                Ok(0) => {
                    // EOF
                    let _ = app_handle.emit("terminal-output", "".to_string());
                    break;
                }
                Ok(n) => {
                    let data = String::from_utf8_lossy(&buf[..n]).to_string();
                    let _ = app_handle.emit("terminal-output", data);
                }
                Err(_) => {
                    break;
                }
            }
        }
    });

    Ok(())
}

/// 向终端写入数据
#[tauri::command]
fn terminal_write(data: String) -> Result<(), String> {
    let pty = PTY_STATE.lock().map_err(|e| format!("锁错误: {}", e))?;
    if let Some(state) = pty.as_ref() {
        let mut writer = state.writer.lock().map_err(|e| format!("锁错误: {}", e))?;
        writer.write_all(data.as_bytes()).map_err(|e| format!("写入失败: {}", e))?;
        writer.flush().map_err(|e| format!("flush 失败: {}", e))?;
    }
    Ok(())
}

/// 调整终端大小
#[tauri::command]
fn terminal_resize(rows: u16, cols: u16) -> Result<(), String> {
    let pty = PTY_STATE.lock().map_err(|e| format!("锁错误: {}", e))?;
    if let Some(state) = pty.as_ref() {
        state
            .master
            .resize(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .map_err(|e| format!("调整大小失败: {}", e))?;
    }
    Ok(())
}

/// 关闭终端
#[tauri::command]
fn terminal_kill() -> Result<(), String> {
    let mut pty = PTY_STATE.lock().map_err(|e| format!("锁错误: {}", e))?;
    *pty = None;
    Ok(())
}

// ==================== 后端进程管理 ====================

/// 全局后端进程句柄
static BACKEND_PROCESS: Mutex<Option<Child>> = Mutex::new(None);

/// 检查 soloncode 命令是否可用
fn find_soloncode_command() -> Option<String> {
    // Windows: 检查 ~/.soloncode/bin/soloncode.ps1 或 .bat
    if cfg!(windows) {
        if let Ok(home) = std::env::var("USERPROFILE") {
            let bin_dir = Path::new(&home).join(".soloncode").join("bin");
            // 优先使用 .ps1（功能更完整）
            let ps1 = bin_dir.join("soloncode.ps1");
            if ps1.exists() {
                return Some(ps1.to_string_lossy().to_string());
            }
            let bat = bin_dir.join("soloncode.bat");
            if bat.exists() {
                return Some(bat.to_string_lossy().to_string());
            }
        }
    }

    // 通用: 尝试 which/where 查找
    let check = Command::new(if cfg!(windows) { "where" } else { "which" })
        .arg("soloncode")
        .output();

    if let Ok(output) = check {
        if output.status.success() {
            let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !path.is_empty() {
                return Some(path.lines().next().unwrap_or(&path).to_string());
            }
        }
    }

    None
}

/// 查找 install-cli 安装脚本路径
fn find_install_script(app_handle: &tauri::AppHandle) -> Option<std::path::PathBuf> {
    // 1. Tauri 打包资源目录中的 build/
    if let Ok(resource_dir) = app_handle.path().resource_dir() {
        if cfg!(windows) {
            let bat = resource_dir.join("build").join("install-cli.bat");
            if bat.exists() { return Some(bat); }
        } else {
            let sh = resource_dir.join("build").join("install-cli.sh");
            if sh.exists() { return Some(sh); }
        }
    }

    // 2. 开发模式：从可执行文件向上查找
    if let Ok(exe_dir) = std::env::current_exe() {
        let mut dir = exe_dir.parent();
        for _ in 0..10 {
            if let Some(d) = dir {
                if cfg!(windows) {
                    let bat = d.join("soloncode-desktop").join("build").join("install-cli.bat");
                    if bat.exists() { return Some(bat); }
                } else {
                    let sh = d.join("soloncode-desktop").join("build").join("install-cli.sh");
                    if sh.exists() { return Some(sh); }
                }
                dir = d.parent();
            } else {
                break;
            }
        }
    }

    None
}

/// 查找 release 目录路径
fn find_release_resource_dir(app_handle: &tauri::AppHandle) -> Option<std::path::PathBuf> {
    // 1. Tauri 打包资源
    if let Ok(resource_dir) = app_handle.path().resource_dir() {
        let release = resource_dir.join("soloncode-cli").join("release");
        if release.join("bin").exists() { return Some(release); }
        // resources 可能直接平铺
        let release_flat = resource_dir.join("release");
        if release_flat.join("bin").exists() { return Some(release_flat); }
    }

    // 2. 开发模式
    if let Ok(exe_dir) = std::env::current_exe() {
        let mut dir = exe_dir.parent();
        for _ in 0..10 {
            if let Some(d) = dir {
                let release = d.join("soloncode-cli").join("release");
                if release.join("bin").exists() { return Some(release); }
                dir = d.parent();
            } else {
                break;
            }
        }
    }

    None
}

/// 自动安装 CLI（通过调用 install-cli 脚本）
fn auto_install_cli(app_handle: &tauri::AppHandle) -> Result<(), String> {
    let install_script = find_install_script(app_handle)
        .ok_or("未找到 install-cli 安装脚本")?;

    let release_dir = find_release_resource_dir(app_handle)
        .ok_or("未找到 soloncode-cli/release 资源目录")?;

    println!("[soloncode] Running install script: {:?}", install_script);
    println!("[soloncode] Release dir: {:?}", release_dir);

    let release_dir_str = release_dir.to_string_lossy().to_string();
    let status = if cfg!(windows) {
        Command::new("cmd")
            .args(["/C", &install_script.to_string_lossy(), &release_dir_str])
            .output()
            .map_err(|e| format!("执行安装脚本失败: {}", e))?
    } else {
        Command::new("bash")
            .arg(&install_script)
            .arg(&release_dir_str)
            .output()
            .map_err(|e| format!("执行安装脚本失败: {}", e))?
    };

    if !status.status.success() {
        let stderr = String::from_utf8_lossy(&status.stderr);
        let stdout = String::from_utf8_lossy(&status.stdout);
        return Err(format!("CLI 安装失败: {}\n{}", stdout, stderr));
    }

    // 输出脚本日志
    let stdout = String::from_utf8_lossy(&status.stdout);
    for line in stdout.lines() {
        println!("{}", line);
    }

    Ok(())
}

/// 启动后端 CLI 进程
#[tauri::command]
fn start_backend(app_handle: tauri::AppHandle, workspace_path: &str, port: u16) -> Result<u32, String> {
    // 先停止已有进程
    stop_backend()?;

    // 查找 soloncode 命令，未找到则自动安装
    let soloncode_cmd = match find_soloncode_command() {
        Some(cmd) => cmd,
        None => {
            println!("[soloncode] CLI not found, auto-installing...");
            auto_install_cli(&app_handle)?;
            find_soloncode_command()
                .ok_or("CLI 安装后仍未找到 soloncode 命令".to_string())?
        }
    };

    // 确保工作区 .soloncode 目录存在
    let soloncode_dir = Path::new(workspace_path).join(".soloncode");
    if !soloncode_dir.exists() {
        fs::create_dir_all(&soloncode_dir)
            .map_err(|e| format!("创建 .soloncode 目录失败: {}", e))?;
    }

    // 日志文件
    let log_path = soloncode_dir.join("cli.log");
    let log_file = fs::File::create(&log_path)
        .map_err(|e| format!("创建日志文件失败: {}", e))?;
    let log_file_clone = log_file.try_clone()
        .map_err(|e| format!("复制文件句柄失败: {}", e))?;

    let child = if cfg!(windows) && soloncode_cmd.ends_with(".ps1") {
        // Windows: 通过 powershell 运行 .ps1
        Command::new("powershell")
            .args([
                "-ExecutionPolicy", "Bypass",
                "-File", &soloncode_cmd,
                &format!("--server.port={}", port),
            ])
            .current_dir(workspace_path)
            .stdout(log_file)
            .stderr(log_file_clone)
            .spawn()
            .map_err(|e| format!("启动后端进程失败: {}", e))?
    } else if cfg!(windows) && soloncode_cmd.ends_with(".bat") {
        // Windows: 通过 cmd 运行 .bat
        Command::new("cmd")
            .args(["/C", &soloncode_cmd, &format!("--server.port={}", port)])
            .current_dir(workspace_path)
            .stdout(log_file)
            .stderr(log_file_clone)
            .spawn()
            .map_err(|e| format!("启动后端进程失败: {}", e))?
    } else {
        // Unix 或非 .ps1: 直接运行
        Command::new(&soloncode_cmd)
            .args([&format!("--server.port={}", port)])
            .current_dir(workspace_path)
            .stdout(log_file)
            .stderr(log_file_clone)
            .spawn()
            .map_err(|e| format!("启动后端进程失败: {}", e))?
    };

    let pid = child.id();

    let mut proc = BACKEND_PROCESS.lock().map_err(|e| format!("锁错误: {}", e))?;
    *proc = Some(child);

    Ok(pid)
}

/// 停止后端 CLI 进程
#[tauri::command]
fn stop_backend() -> Result<(), String> {
    let mut proc = BACKEND_PROCESS.lock().map_err(|e| format!("锁错误: {}", e))?;

    if let Some(mut child) = proc.take() {
        // 尝试优雅终止
        let _ = child.kill();
        let _ = child.wait();
    }

    Ok(())
}

/// 检查后端进程是否运行中
#[tauri::command]
fn backend_status() -> Result<bool, String> {
    let mut proc = BACKEND_PROCESS.lock().map_err(|e| format!("锁错误: {}", e))?;

    match proc.as_mut() {
        Some(child) => {
            // 尝试检查进程状态（非阻塞）
            match child.try_wait() {
                Ok(Some(_status)) => {
                    // 进程已退出
                    *proc = None;
                    Ok(false)
                }
                Ok(None) => Ok(true), // 仍在运行
                Err(e) => Err(format!("检查进程状态失败: {}", e)),
            }
        }
        None => Ok(false),
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .invoke_handler(tauri::generate_handler![
            read_file,
            write_file,
            list_directory,
            list_directory_tree,
            create_file,
            create_directory,
            delete_file,
            delete_directory,
            rename_item,
            path_exists,
            get_workspace_info,
            init_workspace_config,
            git_status,
            git_add,
            git_reset,
            git_commit,
            git_push,
            git_pull,
            git_log,
            git_branches,
            git_checkout,
            git_discard,
            git_diff_file,
            copy_item,
            move_item,
            start_backend,
            stop_backend,
            backend_status,
            terminal_start,
            terminal_write,
            terminal_resize,
            terminal_kill
        ])
        .on_window_event(|_window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                // 应用退出时停止后端进程
                if let Ok(mut proc) = BACKEND_PROCESS.lock() {
                    if let Some(mut child) = proc.take() {
                        let _ = child.kill();
                        let _ = child.wait();
                    }
                }
                // 关闭终端
                if let Ok(mut pty) = PTY_STATE.lock() {
                    *pty = None;
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
