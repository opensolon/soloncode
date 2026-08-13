use crate::process_utils::silent_command;
use serde::Serialize;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

const CHECKPOINT_REF_ROOT: &str = "refs/soloncode/checkpoints";
const MAX_CHECKPOINTS: usize = 30;
const MAX_OUTPUT_BYTES: usize = 200_000;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CheckpointInfo {
    id: String,
    label: String,
    commit: String,
    created_at: u64,
    changed_files: usize,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiagnosticResult {
    command: String,
    success: bool,
    stdout: String,
    stderr: String,
    duration_ms: u128,
    error_count: usize,
    warning_count: usize,
}

fn canonical_workspace(workspace: &str) -> Result<PathBuf, String> {
    if workspace.trim().is_empty() {
        return Err("请先打开工作区".to_string());
    }
    let path = fs::canonicalize(workspace).map_err(|_| "工作区不存在".to_string())?;
    if !path.is_dir() {
        return Err("工作区路径不是目录".to_string());
    }
    Ok(path)
}

fn git_output(repo: &Path, args: &[&str]) -> Result<Vec<u8>, String> {
    let output = silent_command("git")
        .args(args)
        .current_dir(repo)
        .output()
        .map_err(|_| "无法启动 Git".to_string())?;
    if !output.status.success() {
        return Err("Git 操作失败".to_string());
    }
    Ok(output.stdout)
}

fn git_text(repo: &Path, args: &[&str]) -> Result<String, String> {
    String::from_utf8(git_output(repo, args)?)
        .map(|v| v.trim().to_string())
        .map_err(|_| "Git 输出格式无效".to_string())
}

fn git_repo(workspace: &str) -> Result<PathBuf, String> {
    let workspace = canonical_workspace(workspace)?;
    let root = git_text(&workspace, &["rev-parse", "--show-toplevel"])?;
    let root = fs::canonicalize(root).map_err(|_| "无法解析 Git 根目录".to_string())?;
    if root != workspace {
        return Err("请打开 Git 仓库根目录后再创建检查点".to_string());
    }
    Ok(root)
}

fn git_with_temp_index(repo: &Path, index: &Path, args: &[&str]) -> Result<Vec<u8>, String> {
    let output = silent_command("git")
        .args(args)
        .env("GIT_INDEX_FILE", index)
        .env("GIT_AUTHOR_NAME", "SolonCode")
        .env("GIT_AUTHOR_EMAIL", "desktop@soloncode.local")
        .env("GIT_COMMITTER_NAME", "SolonCode")
        .env("GIT_COMMITTER_EMAIL", "desktop@soloncode.local")
        .current_dir(repo)
        .output()
        .map_err(|_| "无法启动 Git".to_string())?;
    if !output.status.success() {
        return Err("创建工作区检查点失败".to_string());
    }
    Ok(output.stdout)
}

fn snapshot_commit(repo: &Path, label: &str) -> Result<String, String> {
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let index = std::env::temp_dir().join(format!(
        "soloncode-checkpoint-{}-{}.index",
        std::process::id(),
        nonce
    ));
    let head = git_text(repo, &["rev-parse", "--verify", "HEAD"]).ok();
    let read_result = if head.is_some() {
        git_with_temp_index(repo, &index, &["read-tree", "HEAD"])
    } else {
        git_with_temp_index(repo, &index, &["read-tree", "--empty"])
    };
    if let Err(error) = read_result {
        let _ = fs::remove_file(&index);
        return Err(error);
    }

    // 常见密钥文件不会写入检查点对象；已跟踪密钥保持 HEAD 版本且恢复时不触碰。
    let add_result = git_with_temp_index(
        repo,
        &index,
        &[
            "add",
            "-A",
            "--",
            ".",
            ":(exclude).env",
            ":(exclude).env.*",
            ":(exclude)**/.env",
            ":(exclude)**/.env.*",
            ":(exclude)**/*.pem",
            ":(exclude)**/*.key",
            ":(exclude)**/credentials*",
            ":(exclude)**/secrets*",
        ],
    );
    if let Err(error) = add_result {
        let _ = fs::remove_file(&index);
        return Err(error);
    }
    let tree =
        String::from_utf8(git_with_temp_index(repo, &index, &["write-tree"])?).unwrap_or_default();
    let tree = tree.trim();
    let safe_label: String = label
        .chars()
        .filter(|c| !c.is_control())
        .take(120)
        .collect();
    let safe_label = if safe_label.trim().is_empty() {
        "桌面端自动检查点"
    } else {
        safe_label.trim()
    };
    let mut args = vec!["commit-tree", tree];
    if let Some(ref parent) = head {
        args.extend(["-p", parent.as_str()]);
    }
    args.extend(["-m", safe_label]);
    let commit = String::from_utf8(git_with_temp_index(repo, &index, &args)?).unwrap_or_default();
    let _ = fs::remove_file(&index);
    let commit = commit.trim().to_string();
    if commit.len() != 40 && commit.len() != 64 {
        return Err("检查点对象无效".to_string());
    }
    Ok(commit)
}

fn validate_checkpoint_id(id: &str) -> Result<(), String> {
    if id.is_empty() || id.len() > 80 || !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
    {
        return Err("检查点标识无效".to_string());
    }
    Ok(())
}

fn checkpoint_ref(id: &str) -> Result<String, String> {
    validate_checkpoint_id(id)?;
    Ok(format!("{}/{}", CHECKPOINT_REF_ROOT, id))
}

fn changed_files(repo: &Path, commit: &str) -> usize {
    git_text(repo, &["diff", "--name-only", "HEAD", commit, "--"])
        .map(|v| v.lines().filter(|line| !line.trim().is_empty()).count())
        .unwrap_or(0)
}

fn list_checkpoints(repo: &Path) -> Result<Vec<CheckpointInfo>, String> {
    let output = git_text(
        repo,
        &[
            "for-each-ref",
            "--sort=-creatordate",
            "--format=%(refname:short)%09%(objectname)%09%(creatordate:unix)%09%(subject)",
            CHECKPOINT_REF_ROOT,
        ],
    )?;
    let mut result = Vec::new();
    for line in output.lines() {
        let mut parts = line.splitn(4, '\t');
        let short_ref = parts.next().unwrap_or_default();
        let commit = parts.next().unwrap_or_default().to_string();
        let created_at = parts.next().unwrap_or("0").parse::<u64>().unwrap_or(0) * 1000;
        let label = parts.next().unwrap_or("工作区检查点").to_string();
        let id = short_ref.rsplit('/').next().unwrap_or_default().to_string();
        if validate_checkpoint_id(&id).is_ok() {
            result.push(CheckpointInfo {
                id,
                label,
                changed_files: changed_files(repo, &commit),
                commit,
                created_at,
            });
        }
    }
    Ok(result)
}

#[tauri::command]
pub fn workspace_checkpoint_create(workspace: &str, label: &str) -> Result<CheckpointInfo, String> {
    let repo = git_repo(workspace)?;
    let commit = snapshot_commit(&repo, label)?;
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    let id = format!("{}-{}", millis, std::process::id());
    let reference = checkpoint_ref(&id)?;
    git_text(&repo, &["update-ref", &reference, &commit])?;

    let checkpoints = list_checkpoints(&repo)?;
    for old in checkpoints.iter().skip(MAX_CHECKPOINTS) {
        if let Ok(old_ref) = checkpoint_ref(&old.id) {
            let _ = git_text(&repo, &["update-ref", "-d", &old_ref]);
        }
    }
    Ok(CheckpointInfo {
        id,
        label: label
            .chars()
            .filter(|c| !c.is_control())
            .take(120)
            .collect(),
        changed_files: changed_files(&repo, &commit),
        commit,
        created_at: millis as u64,
    })
}

#[tauri::command]
pub fn workspace_checkpoint_list(workspace: &str) -> Result<Vec<CheckpointInfo>, String> {
    list_checkpoints(&git_repo(workspace)?)
}

#[tauri::command]
pub fn workspace_checkpoint_restore(workspace: &str, checkpoint_id: &str) -> Result<(), String> {
    let repo = git_repo(workspace)?;
    let reference = checkpoint_ref(checkpoint_id)?;
    let target = git_text(&repo, &["rev-parse", "--verify", &reference])?;
    let current = snapshot_commit(&repo, "恢复前临时快照")?;
    let patch = git_output(&repo, &["diff", "--binary", &current, &target, "--", "."])?;
    if patch.is_empty() {
        return Ok(());
    }
    for check_only in [true, false] {
        let mut command = silent_command("git");
        command.arg("apply").arg("--binary");
        if check_only {
            command.arg("--check");
        }
        let mut child = command
            .current_dir(&repo)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|_| "无法启动 Git".to_string())?;
        child
            .stdin
            .as_mut()
            .ok_or("无法写入恢复数据")?
            .write_all(&patch)
            .map_err(|_| "无法写入恢复数据".to_string())?;
        if !child
            .wait()
            .map_err(|_| "无法等待 Git".to_string())?
            .success()
        {
            return Err(if check_only {
                "当前文件与检查点冲突，未执行恢复"
            } else {
                "恢复检查点失败"
            }
            .to_string());
        }
    }
    Ok(())
}

#[tauri::command]
pub fn workspace_checkpoint_delete(workspace: &str, checkpoint_id: &str) -> Result<(), String> {
    let repo = git_repo(workspace)?;
    let reference = checkpoint_ref(checkpoint_id)?;
    git_text(&repo, &["rev-parse", "--verify", &reference])?;
    git_text(&repo, &["update-ref", "-d", &reference])?;
    Ok(())
}

fn select_diagnostic_command(
    workspace: &Path,
    kind: &str,
) -> Result<(String, Vec<String>), String> {
    if !matches!(kind, "diagnostics" | "build" | "test") {
        return Err("检查类型无效".to_string());
    }
    if kind == "diagnostics" {
        return Ok(("git".to_string(), vec!["diff".into(), "--check".into()]));
    }
    if workspace.join("pom.xml").exists() {
        let exe = if cfg!(windows) { "mvn.cmd" } else { "mvn" };
        let args = if kind == "test" {
            vec!["-B", "-ntp", "test"]
        } else {
            vec!["-B", "-ntp", "-DskipTests", "compile"]
        };
        return Ok((
            exe.to_string(),
            args.into_iter().map(String::from).collect(),
        ));
    }
    if workspace.join("Cargo.toml").exists() {
        return Ok((
            "cargo".to_string(),
            vec![if kind == "test" { "test" } else { "check" }.into()],
        ));
    }
    if workspace.join("go.mod").exists() {
        return Ok(("go".to_string(), vec!["test".into(), "./...".into()]));
    }
    if workspace.join("package.json").exists() {
        let exe = if cfg!(windows) { "npm.cmd" } else { "npm" };
        return Ok((
            exe.to_string(),
            vec![
                if kind == "test" { "test" } else { "run" }.into(),
                if kind == "test" { "--" } else { "build" }.into(),
            ],
        ));
    }
    Err("未识别到可检查的项目类型".to_string())
}

fn truncate_output(bytes: &[u8]) -> String {
    let start = bytes.len().saturating_sub(MAX_OUTPUT_BYTES);
    String::from_utf8_lossy(&bytes[start..]).to_string()
}

#[tauri::command]
pub async fn run_workspace_check(
    workspace: String,
    kind: String,
) -> Result<DiagnosticResult, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let workspace = canonical_workspace(&workspace)?;
        let (program, args) = select_diagnostic_command(&workspace, &kind)?;
        let display = std::iter::once(program.as_str())
            .chain(args.iter().map(String::as_str))
            .collect::<Vec<_>>()
            .join(" ");
        let started = Instant::now();
        let output = silent_command(&program)
            .args(&args)
            .current_dir(&workspace)
            .output()
            .map_err(|_| "无法启动项目检查命令".to_string())?;
        let stdout = truncate_output(&output.stdout);
        let stderr = truncate_output(&output.stderr);
        let combined = format!("{}\n{}", stdout, stderr).to_ascii_lowercase();
        Ok(DiagnosticResult {
            command: display,
            success: output.status.success(),
            error_count: combined
                .lines()
                .filter(|line| line.contains("error"))
                .count(),
            warning_count: combined
                .lines()
                .filter(|line| line.contains("warning"))
                .count(),
            stdout,
            stderr,
            duration_ms: started.elapsed().as_millis(),
        })
    })
    .await
    .map_err(|_| "项目检查任务异常终止".to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Command;

    #[cfg(target_os = "windows")]
    #[test]
    fn git_commands_do_not_create_console_windows() {
        const PROBE_ENV: &str = "SOLONCODE_DESKTOP_CONSOLE_PROBE";

        if std::env::var_os(PROBE_ENV).is_some() {
            extern "system" {
                fn FreeConsole() -> i32;
            }

            // Simulate the release desktop process, which has no attached console.
            unsafe {
                FreeConsole();
            }

            let nonce = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            let root = std::env::temp_dir().join(format!("soloncode-console-probe-{nonce}"));
            fs::create_dir_all(&root).unwrap();
            git_output(&root, &["init"]).unwrap();

            let script = "Add-Type -Name NativeConsole -Namespace Win32 -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern IntPtr GetConsoleWindow();'; [Win32.NativeConsole]::GetConsoleWindow().ToInt64()";
            let script_bytes: Vec<u8> = script
                .encode_utf16()
                .flat_map(|unit| unit.to_le_bytes())
                .collect();
            let encoded =
                base64::Engine::encode(&base64::engine::general_purpose::STANDARD, script_bytes);
            let alias = format!(
                "alias.console=!powershell.exe -NoProfile -NonInteractive -EncodedCommand {encoded}"
            );
            let output =
                String::from_utf8(git_output(&root, &["-c", &alias, "console"]).unwrap()).unwrap();
            let console_handle = output
                .lines()
                .find_map(|line| line.trim().parse::<u64>().ok())
                .expect("PowerShell should report its console window handle");

            let canonical_root = fs::canonicalize(&root).unwrap();
            let canonical_temp = fs::canonicalize(std::env::temp_dir()).unwrap();
            assert!(canonical_root.starts_with(canonical_temp));
            fs::remove_dir_all(canonical_root).unwrap();
            assert_eq!(console_handle, 0, "Git descendants must remain windowless");
            return;
        }

        let status = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "desktop_ops::tests::git_commands_do_not_create_console_windows",
                "--nocapture",
            ])
            .env(PROBE_ENV, "1")
            .status()
            .unwrap();
        assert!(status.success(), "windowless Git probe failed");
    }

    #[test]
    fn checkpoint_restores_workspace_but_not_secret_files() {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!("soloncode-checkpoint-test-{nonce}"));
        fs::create_dir_all(&root).unwrap();
        Command::new("git")
            .arg("init")
            .current_dir(&root)
            .output()
            .unwrap();
        fs::write(root.join("notes.txt"), "before\n").unwrap();
        fs::write(root.join(".env"), "TOKEN=before\n").unwrap();

        let workspace = root.to_string_lossy().to_string();
        let checkpoint = workspace_checkpoint_create(&workspace, "test checkpoint").unwrap();
        fs::write(root.join("notes.txt"), "after\n").unwrap();
        fs::write(root.join(".env"), "TOKEN=after\n").unwrap();

        workspace_checkpoint_restore(&workspace, &checkpoint.id).unwrap();
        assert_eq!(
            fs::read_to_string(root.join("notes.txt")).unwrap().trim(),
            "before"
        );
        assert_eq!(
            fs::read_to_string(root.join(".env")).unwrap().trim(),
            "TOKEN=after"
        );

        let canonical_root = fs::canonicalize(&root).unwrap();
        let canonical_temp = fs::canonicalize(std::env::temp_dir()).unwrap();
        assert!(canonical_root.starts_with(canonical_temp));
        fs::remove_dir_all(canonical_root).unwrap();
    }
}
