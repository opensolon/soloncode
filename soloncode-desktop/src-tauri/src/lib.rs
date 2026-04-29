use serde::{Deserialize, Serialize};
use std::fs;
use std::io::{Read as IoRead, Write as IoWrite};
use base64::Engine;
use std::path::Path;
use std::process::{Child, Command};
use std::sync::Mutex;
use std::net::TcpStream;
use std::time::SystemTime;
use tauri::{Manager, Emitter};
use portable_pty::{native_pty_system, PtySize, CommandBuilder as PtyCommandBuilder};

/// 应用日志文件
static APP_LOG: Mutex<Option<std::fs::File>> = Mutex::new(None);

/// 写入应用日志
fn app_log(msg: &str) {
    let timestamp = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let line = format!("[{}] {}\n", timestamp, msg);
    println!("{}", line.trim());
    if let Ok(mut log_file) = APP_LOG.lock() {
        if let Some(f) = log_file.as_mut() {
            let _ = f.write_all(line.as_bytes());
        }
    }
}

/// 初始化日志文件（保存在应用目录下，每次启动重置）
fn init_app_log() {
    if let Ok(exe) = std::env::current_exe() {
        if let Some(app_dir) = exe.parent() {
            let log_path = app_dir.join("desktop.log");
            // 每次启动重置日志文件
            if let Ok(f) = fs::File::create(&log_path) {
                if let Ok(mut log_file) = APP_LOG.lock() {
                    *log_file = Some(f);
                }
            }
        }
    }
}

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

/// 读取文件为 Base64（用于图片等二进制文件预览）
#[tauri::command]
fn read_file_binary(path: &str) -> Result<String, String> {
    let data = fs::read(path).map_err(|e| format!("读取文件失败: {}", e))?;
    Ok(base64::engine::general_purpose::STANDARD.encode(&data))
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

/// 获取文件在 HEAD 中的内容（原始版本）
#[tauri::command]
fn git_show_head(cwd: &str, file_path: &str) -> Result<String, String> {
    run_git(&["show", &format!("HEAD:{}", file_path)], cwd)
}

/// 获取文件的完整 diff 文本（unified diff 格式）
#[tauri::command]
fn git_diff_text(cwd: &str, file_path: &str) -> Result<String, String> {
    match run_git(&["diff", "HEAD", "--", file_path], cwd) {
        Ok(o) => {
            if o.trim().is_empty() {
                match run_git(&["diff", "--cached", "--", file_path], cwd) {
                    Ok(o2) => Ok(o2),
                    Err(_) => Ok(o),
                }
            } else {
                Ok(o)
            }
        }
        Err(_) => run_git(&["diff", "--", file_path], cwd),
    }
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

    let mut cmd = PtyCommandBuilder::new("powershell");
    if let Some(dir) = cwd {
        cmd.cwd(dir);
    }

    let child = pair
        .slave
        .spawn_command(cmd)
        .map_err(|e| format!("启动 PowerShell 失败: {}", e))?;

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
    // 1. Tauri 资源目录（生产模式：build/install-cli.*）
    if let Ok(resource_dir) = app_handle.path().resource_dir() {
        let script_name = if cfg!(windows) { "install-cli.bat" } else { "install-cli.sh" };
        let script = resource_dir.join("build").join(script_name);
        if script.exists() { return Some(script); }
    }

    // 2. 开发模式：从可执行文件向上查找
    if let Ok(exe_dir) = std::env::current_exe() {
        let mut dir = exe_dir.parent();
        let script_name = if cfg!(windows) { "install-cli.bat" } else { "install-cli.sh" };
        for _ in 0..10 {
            if let Some(d) = dir {
                let script = d.join("soloncode-desktop").join("build").join(script_name);
                if script.exists() { return Some(script); }
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
    // 1. Tauri 资源目录（生产模式：release/）
    if let Ok(resource_dir) = app_handle.path().resource_dir() {
        let release = resource_dir.join("release");
        if release.join("bin").exists() { return Some(release); }
    }

    // 2. 开发模式：从可执行文件向上查找
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

/// 自动安装 CLI：将打包资源中的 JAR 复制到 ~/.soloncode/bin/
fn auto_install_cli(app_handle: &tauri::AppHandle) -> Result<(), String> {
    let home_var = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
    let home = std::env::var(home_var).unwrap_or_default();
    let target_bin = Path::new(&home).join(".soloncode").join("bin");

    // 查找打包资源中的 JAR
    let source_jar = if let Ok(resource_dir) = app_handle.path().resource_dir() {
        let jar = resource_dir.join("target").join("soloncode-cli.jar");
        if jar.exists() { Some(jar) } else { None }
    } else { None };

    let source_jar = source_jar.or_else(|| {
        // 开发模式回退
        if let Ok(exe_dir) = std::env::current_exe() {
            let mut dir = exe_dir.parent();
            for _ in 0..10 {
                if let Some(d) = dir {
                    let jar = d.join("soloncode-cli").join("target").join("soloncode-cli.jar");
                    if jar.exists() { return Some(jar); }
                    dir = d.parent();
                } else { break; }
            }
        }
        None
    });

    // 创建目标目录
    fs::create_dir_all(&target_bin)
        .map_err(|e| format!("创建目录失败: {}", e))?;

    if let Some(jar) = source_jar {
        let dest = target_bin.join("soloncode-cli.jar");
        app_log(&format!("[soloncode] Copying JAR: {:?} -> {:?}", jar, dest));
        fs::copy(&jar, &dest)
            .map_err(|e| format!("复制 JAR 失败: {}", e))?;
        app_log("[soloncode] JAR copied successfully");
    } else {
        return Err("未找到 soloncode-cli.jar 资源".to_string());
    }

    // 运行安装脚本（复制 release/、创建启动脚本、注册 PATH）
    if let Some(install_script) = find_install_script(app_handle) {
        if let Some(release_dir) = find_release_resource_dir(app_handle) {
            app_log(&format!("[soloncode] Running install script: {:?}", install_script));
            let release_dir_str = release_dir.to_string_lossy().to_string();
            let status = if cfg!(windows) {
                let mut install_cmd = Command::new("cmd");
                install_cmd.args(["/C", &install_script.to_string_lossy(), &release_dir_str]);
                #[cfg(target_os = "windows")]
                {
                    use std::os::windows::process::CommandExt;
                    install_cmd.creation_flags(0x08000000);
                }
                install_cmd.output()
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
                app_log(&format!("[soloncode] Install script failed: {}\n{}", stdout, stderr));
            } else {
                let stdout = String::from_utf8_lossy(&status.stdout);
                for line in stdout.lines() {
                    app_log(line);
                }
            }
        }
    }

    Ok(())
}

/// 查找打包资源中的 JAR（生产模式：resource_dir/target/，开发模式：向上查找）
fn find_bundled_jar(app_handle: &tauri::AppHandle) -> Option<std::path::PathBuf> {
    // 1. Tauri 资源目录（生产模式）
    if let Ok(resource_dir) = app_handle.path().resource_dir() {
        let jar = resource_dir.join("target").join("soloncode-cli.jar");
        if jar.exists() {
            app_log(&format!("[soloncode] Found bundled JAR: {:?}", jar));
            return Some(jar);
        }
    }

    // 2. 开发模式：从可执行文件向上查找 soloncode-cli/target/soloncode-cli.jar
    if let Ok(exe_dir) = std::env::current_exe() {
        let mut dir = exe_dir.parent();
        for _ in 0..10 {
            if let Some(d) = dir {
                let jar = d.join("soloncode-cli").join("target").join("soloncode-cli.jar");
                if jar.exists() {
                    app_log(&format!("[soloncode] Found JAR in dev mode: {:?}", jar));
                    return Some(jar);
                }
                dir = d.parent();
            } else {
                break;
            }
        }
    }

    None
}

/// 查找 soloncode-cli.jar 文件路径
/// 优先使用打包资源中的 JAR（确保版本最新），回退到 ~/.soloncode/bin/
fn find_cli_jar(app_handle: &tauri::AppHandle) -> Option<std::path::PathBuf> {
    // 1. 打包资源中的 JAR（生产模式或开发模式）
    if let Some(bundled) = find_bundled_jar(app_handle) {
        // 将打包 JAR 同步到 ~/.soloncode/bin/（确保安装目录版本最新）
        let home_var = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
        if let Ok(home) = std::env::var(home_var) {
            let target_bin = Path::new(&home).join(".soloncode").join("bin");
            let _ = fs::create_dir_all(&target_bin);
            let dest = target_bin.join("soloncode-cli.jar");
            match fs::copy(&bundled, &dest) {
                Ok(_) => app_log(&format!("[soloncode] Synced bundled JAR to {:?}", dest)),
                Err(e) => app_log(&format!("[soloncode] Failed to sync JAR: {}, using bundled directly", e)),
            }
        }
        return Some(bundled);
    }

    // 2. 回退：~/.soloncode/bin/soloncode-cli.jar（无打包资源时）
    let home_var = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
    if let Ok(home) = std::env::var(home_var) {
        let jar = Path::new(&home).join(".soloncode").join("bin").join("soloncode-cli.jar");
        if jar.exists() {
            app_log(&format!("[soloncode] Using fallback JAR: {:?}", jar));
            return Some(jar);
        }
    }

    app_log("[soloncode] JAR not found, will trigger auto-install");
    None
}

/// 启动后端 CLI 进程（如果已在运行则复用）
#[tauri::command]
fn start_backend(app_handle: tauri::AppHandle, workspace_path: &str, port: u16) -> Result<u32, String> {
    // 检查已有进程是否仍在运行
    {
        let mut proc = BACKEND_PROCESS.lock().map_err(|e| format!("锁错误: {}", e))?;
        match proc.as_mut() {
            Some(child) => {
                match child.try_wait() {
                    Ok(Some(_status)) => {
                        *proc = None;
                    }
                    Ok(None) => {
                        app_log(&format!("[soloncode] Backend already running, reusing PID {}", child.id()));
                        return Ok(child.id());
                    }
                    Err(e) => {
                        app_log(&format!("[soloncode] Failed to check process status: {}, restarting...", e));
                        *proc = None;
                    }
                }
            }
            None => {}
        }
    }

    // 检查端口是否被占用
    if TcpStream::connect(format!("127.0.0.1:{}", port)).is_ok() {
        let msg = format!("端口 {} 已被占用，请先关闭占用该端口的程序", port);
        app_log(&format!("[soloncode] ERROR: {}", msg));
        return Err(msg);
    }

    // 查找 JAR 文件，未找到则自动安装
    let jar_path = match find_cli_jar(&app_handle) {
        Some(p) => p,
        None => {
            app_log("[soloncode] CLI JAR not found, auto-installing...");
            auto_install_cli(&app_handle)?;
            // 安装后从 ~/.soloncode/bin/ 查找
            let home_var = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
            let home = std::env::var(home_var).unwrap_or_default();
            let installed_jar = Path::new(&home).join(".soloncode").join("bin").join("soloncode-cli.jar");
            if installed_jar.exists() {
                app_log(&format!("[soloncode] Found installed JAR: {:?}", installed_jar));
                installed_jar
            } else {
                return Err("CLI 安装后仍未找到 soloncode-cli.jar".to_string());
            }
        }
    };

    app_log(&format!("[soloncode] Using JAR: {:?}", jar_path));

    // 确定工作目录（空路径时使用用户主目录）
    let work_dir = if workspace_path.is_empty() {
        let home_var = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
        std::env::var(home_var).unwrap_or_else(|_| ".".to_string())
    } else {
        workspace_path.to_string()
    };

    // 确保工作区 .soloncode 目录存在
    let soloncode_dir = Path::new(&work_dir).join(".soloncode");
    if !soloncode_dir.exists() {
        fs::create_dir_all(&soloncode_dir)
            .map_err(|e| format!("创建 .soloncode 目录失败: {}", e))?;
    }

    // 日志文件（保存在应用根目录）
    let log_path = if let Ok(exe) = std::env::current_exe() {
        exe.parent()
            .map(|d| d.join("server.log"))
            .ok_or("无法获取应用目录")?
    } else {
        soloncode_dir.join("server.log")
    };
    let log_file = fs::File::create(&log_path)
        .map_err(|e| format!("创建日志文件失败: {}", e))?;
    let log_file_clone = log_file.try_clone()
        .map_err(|e| format!("复制文件句柄失败: {}", e))?;

    let jar_str = jar_path.to_string_lossy().to_string();
    let port_str = port.to_string();

    app_log(&format!("[soloncode] Starting: java -jar {} serve {}", jar_str, port_str));

    let mut cmd = Command::new("java");
    cmd.args([
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Dstdin.encoding=UTF-8",
        "-jar", &jar_str,
        "serve",
        &port_str,
    ])
    .current_dir(&work_dir)
    .stdout(log_file)
    .stderr(log_file_clone);

    // Windows 下隐藏控制台窗口
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
    }

    let child = cmd.spawn()
        .map_err(|e| {
            let msg = format!("启动后端进程失败: {}", e);
            app_log(&format!("[soloncode] ERROR: {}", msg));
            msg
        })?;

    let pid = child.id();
    app_log(&format!("[soloncode] Backend started, PID={}", pid));

    let mut proc = BACKEND_PROCESS.lock().map_err(|e| format!("锁错误: {}", e))?;
    *proc = Some(child);

    Ok(pid)
}

/// 停止后端 CLI 进程
#[tauri::command]
fn stop_backend() -> Result<(), String> {
    let mut proc = BACKEND_PROCESS.lock().map_err(|e| format!("锁错误: {}", e))?;
    if let Some(mut child) = proc.take() {
        let _ = child.kill();
        let _ = child.wait();
        app_log("[soloncode] Backend process stopped");
    }
    Ok(())
}

// ==================== 配置文件读写 ====================

/// 读取桌面端日志（从应用目录读取）
/// 前端写入应用日志
#[tauri::command]
fn write_app_log(message: &str) {
    app_log(&format!("[frontend] {}", message));
}

/// 读取桌面端日志（从应用目录读取）
#[tauri::command]
fn read_desktop_log() -> Result<String, String> {
    let exe = std::env::current_exe().map_err(|e| format!("无法获取应用路径: {}", e))?;
    let log_path = exe.parent()
        .ok_or("无法获取应用目录")?
        .join("desktop.log");
    if log_path.exists() {
        let content = fs::read_to_string(&log_path)
            .map_err(|e| format!("读取日志失败: {}", e))?;
        // 返回最后 200 行
        let lines: Vec<&str> = content.lines().rev().take(200).collect();
        Ok(lines.into_iter().rev().collect::<Vec<_>>().join("\n"))
    } else {
        Ok("日志文件不存在".to_string())
    }
}

/// 读取服务端日志（从应用根目录读取）
#[tauri::command]
fn read_cli_log(_workspace_path: &str) -> Result<String, String> {
    let log_path = std::env::current_exe()
        .map_err(|e| format!("无法获取应用路径: {}", e))?
        .parent()
        .ok_or("无法获取应用目录")?
        .join("server.log");
    if log_path.exists() {
        let content = fs::read_to_string(&log_path)
            .map_err(|e| format!("读取日志失败: {}", e))?;
        let lines: Vec<&str> = content.lines().rev().take(200).collect();
        Ok(lines.into_iter().rev().collect::<Vec<_>>().join("\n"))
    } else {
        Ok("CLI 日志文件不存在".to_string())
    }
}

/// 读取 ~/.soloncode/config.yml 中的 chatModel 配置
/// 返回 { apiUrl, apiKey, model } 的 JSON 字符串
#[tauri::command]
fn read_global_chat_model() -> Result<serde_json::Value, String> {
    let home = if cfg!(windows) {
        std::env::var("USERPROFILE").unwrap_or_default()
    } else {
        std::env::var("HOME").unwrap_or_default()
    };
    let config_path = Path::new(&home).join(".soloncode").join("config.yml");

    // 先尝试 chat-model.yml（前端推送写入的文件）
    let chat_model_path = Path::new(&home).join(".soloncode").join("chat-model.yml");

    let mut api_url = String::new();
    let mut api_key = String::new();
    let mut model = String::new();

    // 解析 YAML 中的 chatModel 字段（简单行解析，避免引入 yaml 依赖）
    let parse_chat_model = |content: &str, api_url: &mut String, api_key: &mut String, model: &mut String| {
        let mut in_chat_model = false;
        let mut in_soloncode = false;
        for line in content.lines() {
            let trimmed = line.trim();
            if trimmed.starts_with("soloncode:") {
                in_soloncode = true;
                in_chat_model = false;
                continue;
            }
            if in_soloncode && trimmed.starts_with("chatModel:") {
                in_chat_model = true;
                continue;
            }
            if in_chat_model {
                // 检查缩进是否还在 chatModel 块内（至少4个空格）
                let indent = line.len() - line.trim_start().len();
                if indent < 4 && !trimmed.is_empty() {
                    in_chat_model = false;
                    in_soloncode = false;
                    continue;
                }
                if trimmed.starts_with("apiUrl:") {
                    *api_url = trimmed.trim_start_matches("apiUrl:").trim()
                        .trim_matches('"').to_string();
                } else if trimmed.starts_with("apiKey:") {
                    *api_key = trimmed.trim_start_matches("apiKey:").trim()
                        .trim_matches('"').to_string();
                } else if trimmed.starts_with("model:") {
                    *model = trimmed.trim_start_matches("model:").trim()
                        .trim_matches('"').to_string();
                }
            }
        }
    };

    // 优先读 chat-model.yml
    if chat_model_path.exists() {
        if let Ok(content) = fs::read_to_string(&chat_model_path) {
            parse_chat_model(&content, &mut api_url, &mut api_key, &mut model);
        }
    }

    // 再读 config.yml 补充缺失字段
    if config_path.exists() {
        if let Ok(content) = fs::read_to_string(&config_path) {
            let mut u = String::new();
            let mut k = String::new();
            let mut m = String::new();
            parse_chat_model(&content, &mut u, &mut k, &mut m);
            if api_url.is_empty() { api_url = u; }
            if api_key.is_empty() { api_key = k; }
            if model.is_empty() { model = m; }
        }
    }

    Ok(serde_json::json!({
        "apiUrl": api_url,
        "apiKey": api_key,
        "model": model,
    }))
}

/// 读取 ~/.soloncode/skills/ 目录下的 skill 列表
/// 每个 skill 是一个子目录，包含 SKILL.md
#[derive(Debug, Serialize, Deserialize)]
pub struct SkillInfo {
    name: String,
    description: String,
    path: String,
    enabled: bool,
}

#[tauri::command]
fn list_skills() -> Result<Vec<SkillInfo>, String> {
    let home = if cfg!(windows) {
        std::env::var("USERPROFILE").unwrap_or_default()
    } else {
        std::env::var("HOME").unwrap_or_default()
    };

    let skills_dir = Path::new(&home).join(".soloncode").join("skills");
    if !skills_dir.exists() {
        return Ok(vec![]);
    }

    let mut skills = Vec::new();
    let entries = fs::read_dir(&skills_dir).map_err(|e| format!("读取 skills 目录失败: {}", e))?;

    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() { continue; }

        let skill_md = path.join("SKILL.md");
        let mut name = path.file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        let mut description = String::new();

        // 解析 SKILL.md frontmatter
        if skill_md.exists() {
            if let Ok(content) = fs::read_to_string(&skill_md) {
                let mut in_frontmatter = false;
                let mut fence_count = 0;
                for line in content.lines() {
                    if line.trim() == "---" {
                        fence_count += 1;
                        if fence_count == 1 { in_frontmatter = true; continue; }
                        if fence_count == 2 { break; }
                    }
                    if in_frontmatter && fence_count < 2 {
                        if line.starts_with("name:") {
                            name = line.trim_start_matches("name:").trim()
                                .trim_matches('"').to_string();
                        } else if line.starts_with("description:") {
                            description = line.trim_start_matches("description:").trim()
                                .trim_matches('"').to_string();
                            // 截断过长描述
                            if description.len() > 120 {
                                description = format!("{}...", &description[..120]);
                            }
                        }
                    }
                }
            }
        }

        // 检查是否启用（disabled 文件标记）
        let disabled_marker = path.join(".disabled");
        let enabled = !disabled_marker.exists();

        skills.push(SkillInfo {
            name,
            description,
            path: path.to_string_lossy().to_string(),
            enabled,
        });
    }

    skills.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(skills)
}

/// 切换 skill 启用/禁用状态
#[tauri::command]
fn toggle_skill(skill_path: &str, enabled: bool) -> Result<(), String> {
    let disabled_marker = Path::new(skill_path).join(".disabled");
    if enabled {
        if disabled_marker.exists() {
            fs::remove_file(&disabled_marker).map_err(|e| format!("移除标记失败: {}", e))?;
        }
    } else {
        fs::write(&disabled_marker, "").map_err(|e| format!("创建标记失败: {}", e))?;
    }
    Ok(())
}

/// 读取 ~/.soloncode/agents/ 目录下的 agent 列表
/// 每个 agent 是一个子目录，包含 AGENT.md
#[derive(Debug, Serialize, Deserialize)]
pub struct AgentInfo {
    name: String,
    description: String,
    path: String,
    enabled: bool,
}

#[tauri::command]
fn list_agents() -> Result<Vec<AgentInfo>, String> {
    let home = if cfg!(windows) {
        std::env::var("USERPROFILE").unwrap_or_default()
    } else {
        std::env::var("HOME").unwrap_or_default()
    };

    let agents_dir = Path::new(&home).join(".soloncode").join("agents");
    if !agents_dir.exists() {
        return Ok(vec![]);
    }

    let mut agents = Vec::new();
    let entries = fs::read_dir(&agents_dir).map_err(|e| format!("读取 agents 目录失败: {}", e))?;

    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() { continue; }

        let agent_md = path.join("AGENT.md");
        let mut name = path.file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        let mut description = String::new();

        if agent_md.exists() {
            if let Ok(content) = fs::read_to_string(&agent_md) {
                let mut in_frontmatter = false;
                let mut fence_count = 0;
                for line in content.lines() {
                    if line.trim() == "---" {
                        fence_count += 1;
                        if fence_count == 1 { in_frontmatter = true; continue; }
                        if fence_count == 2 { break; }
                    }
                    if in_frontmatter && fence_count < 2 {
                        if line.starts_with("name:") {
                            name = line.trim_start_matches("name:").trim()
                                .trim_matches('"').to_string();
                        } else if line.starts_with("description:") {
                            description = line.trim_start_matches("description:").trim()
                                .trim_matches('"').to_string();
                            if description.len() > 120 {
                                description = format!("{}...", &description[..120]);
                            }
                        }
                    }
                }
            }
        }

        let disabled_marker = path.join(".disabled");
        let enabled = !disabled_marker.exists();

        agents.push(AgentInfo {
            name,
            description,
            path: path.to_string_lossy().to_string(),
            enabled,
        });
    }

    agents.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(agents)
}

/// 切换 agent 启用/禁用状态
#[tauri::command]
fn toggle_agent(agent_path: &str, enabled: bool) -> Result<(), String> {
    let disabled_marker = Path::new(agent_path).join(".disabled");
    if enabled {
        if disabled_marker.exists() {
            fs::remove_file(&disabled_marker).map_err(|e| format!("移除标记失败: {}", e))?;
        }
    } else {
        fs::write(&disabled_marker, "").map_err(|e| format!("创建标记失败: {}", e))?;
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
    init_app_log();
    app_log("[soloncode] Desktop app starting...");

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .invoke_handler(tauri::generate_handler![
            read_file,
            read_file_binary,
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
            git_show_head,
            git_diff_text,
            copy_item,
            move_item,
            start_backend,
            stop_backend,
            backend_status,
            terminal_start,
            terminal_write,
            terminal_resize,
            terminal_kill,
            read_global_chat_model,
            write_app_log,
            read_desktop_log,
            read_cli_log,
            list_skills,
            toggle_skill,
            list_agents,
            toggle_agent
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
