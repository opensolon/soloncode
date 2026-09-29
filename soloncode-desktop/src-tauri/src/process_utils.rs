use std::ffi::OsStr;
use std::process::Command;

#[cfg(target_os = "windows")]
const CREATE_NO_WINDOW: u32 = 0x08000000;

/// Builds a non-interactive child process without allocating a Windows console.
pub fn silent_command<S: AsRef<OsStr>>(program: S) -> Command {
    let mut command = Command::new(program);

    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(CREATE_NO_WINDOW);
    }

    command
}
