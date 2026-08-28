#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const usage = `Usage: npm run package -- [windows|mac|linux]

Options:
  --dry-run  Print the packaging steps without executing them.
  --help     Show this help message.
`;

const hostPlatforms = {
  win32: 'windows',
  darwin: 'mac',
  linux: 'linux',
};
const supportedPlatforms = new Set(Object.values(hostPlatforms));

function fail(message) {
  process.stderr.write(`[package] ERROR: ${message}\n`);
  process.exit(1);
}

function formatCommand(command, args) {
  return [command, ...args].join(' ');
}

function quoteCmdArgument(value) {
  if (!/[\s&|<>^()"]/.test(value)) {
    return value;
  }
  return `"${value.replaceAll('"', '""')}"`;
}

function run(command, args, cwd, dryRun) {
  process.stdout.write(`[package] ${formatCommand(command, args)}\n`);
  if (dryRun) {
    return;
  }

  const isWindowsScript = process.platform === 'win32' && /\.(?:cmd|bat)$/i.test(command);
  const executable = isWindowsScript ? (process.env.ComSpec ?? 'cmd.exe') : command;
  const executableArgs = isWindowsScript
    ? ['/d', '/s', '/c', [command, ...args].map(quoteCmdArgument).join(' ')]
    : args;
  const result = spawnSync(executable, executableArgs, { cwd, stdio: 'inherit' });
  if (result.error) {
    fail(`failed to start ${command}: ${result.error.message}`);
  }
  if (result.status !== 0) {
    fail(`${command} exited with code ${result.status}`);
  }
}

export function readDesktopVersion(configPath) {
  const desktopVersion = JSON.parse(readFileSync(configPath, 'utf8')).version;
  if (typeof desktopVersion !== 'string' || !/^[0-9A-Za-z][0-9A-Za-z._-]*$/.test(desktopVersion)) {
    throw new Error(`invalid desktop version in ${configPath}`);
  }
  return desktopVersion;
}

export function desktopCliJarName(version) {
  if (typeof version !== 'string' || !/^[0-9A-Za-z][0-9A-Za-z._-]*$/.test(version)) {
    throw new Error(`invalid desktop version: ${version}`);
  }
  return `soloncode-cli_${version}.jar`;
}

function validateInstallerJarName(fileName) {
  if (!/^soloncode-cli_[0-9A-Za-z][0-9A-Za-z._-]*\.jar$/.test(fileName)) {
    throw new Error(`invalid installer CLI JAR name: ${fileName}`);
  }
  return fileName;
}

export function renderNsisHook(fileName) {
  const destinationName = validateInstallerJarName(fileName);
  return `!macro NSIS_HOOK_POSTINSTALL
  ClearErrors
  CreateDirectory "$PROFILE\\.soloncode\\bin"
  IfErrors 0 +2
    Abort "Failed to create $PROFILE\\.soloncode\\bin"

  InitPluginsDir
  ClearErrors
  CopyFiles /SILENT "$INSTDIR\\soloncode-cli.jar" "$PLUGINSDIR"
  IfErrors 0 +2
    Abort "Failed to read the bundled soloncode-cli.jar"

  Delete "$PROFILE\\.soloncode\\bin\\${destinationName}"
  ClearErrors
  Rename "$PLUGINSDIR\\soloncode-cli.jar" "$PROFILE\\.soloncode\\bin\\${destinationName}"
  IfErrors 0 +2
    Abort "Failed to install ${destinationName}"
!macroend
`;
}

export function renderMacPostInstall(fileName) {
  const destinationName = validateInstallerJarName(fileName);
  return `#!/bin/sh
set -eu

target_user="$(stat -f '%Su' /dev/console)"
if [ -z "$target_user" ] || [ "$target_user" = "root" ] || [ "$target_user" = "loginwindow" ]; then
  echo "Cannot determine the active macOS user" >&2
  exit 1
fi

home_record="$(dscl . -read "/Users/$target_user" NFSHomeDirectory)"
home_dir="\${home_record#NFSHomeDirectory: }"
if [ -z "$home_dir" ] || [ "$home_dir" = "$home_record" ]; then
  echo "Cannot determine the home directory for $target_user" >&2
  exit 1
fi

target_group="$(id -gn "$target_user")"
source_jar="\${3%/}/Applications/soloncode-desktop.app/Contents/Resources/soloncode-cli.jar"
destination_dir="$home_dir/.soloncode/bin"
destination_jar="$home_dir/.soloncode/bin/${destinationName}"

install -d -m 0755 -o "$target_user" -g "$target_group" "$home_dir/.soloncode" "$destination_dir"
install -m 0644 -o "$target_user" -g "$target_group" "$source_jar" "$destination_jar"
`;
}

export function renderLinuxPostInstall(fileName) {
  const destinationName = validateInstallerJarName(fileName);
  return `#!/bin/sh
set -eu

if [ -n "\${SUDO_USER:-}" ] && [ "$SUDO_USER" != "root" ]; then
  user_record="$(getent passwd "$SUDO_USER")" || {
    echo "Cannot resolve SUDO_USER $SUDO_USER" >&2
    exit 1
  }
elif [ -n "\${PKEXEC_UID:-}" ]; then
  user_record="$(getent passwd "$PKEXEC_UID")" || {
    echo "Cannot resolve PKEXEC_UID $PKEXEC_UID" >&2
    exit 1
  }
else
  echo "Cannot determine the desktop user; install through sudo or a desktop package installer" >&2
  exit 1
fi

IFS=: read -r target_user _ target_uid target_gid _ home_dir _ <<EOF
$user_record
EOF
if [ -z "$target_user" ] || [ "$target_user" = "root" ] || [ -z "$home_dir" ]; then
  echo "Resolved desktop user is invalid" >&2
  exit 1
fi

source_jar="/usr/lib/soloncode-desktop/soloncode-cli.jar"
destination_dir="$home_dir/.soloncode/bin"
destination_jar="$home_dir/.soloncode/bin/${destinationName}"

install -d -m 0755 -o "$target_uid" -g "$target_gid" "$home_dir/.soloncode" "$destination_dir"
install -m 0644 -o "$target_uid" -g "$target_gid" "$source_jar" "$destination_jar"
`;
}

export function writeInstallerHook(targetPlatform, hookRoot, fileName) {
  const hooks = {
    windows: {
      path: path.join(hookRoot, 'windows', 'install-cli.nsh'),
      content: renderNsisHook(fileName),
      executable: false,
    },
    mac: {
      path: path.join(hookRoot, 'mac', 'postinstall'),
      content: renderMacPostInstall(fileName),
      executable: true,
    },
    linux: {
      path: path.join(hookRoot, 'linux', 'postinst'),
      content: renderLinuxPostInstall(fileName),
      executable: true,
    },
  };
  const hook = hooks[targetPlatform];
  if (!hook) {
    throw new Error(`unsupported installer hook platform: ${targetPlatform}`);
  }

  mkdirSync(path.dirname(hook.path), { recursive: true });
  writeFileSync(hook.path, hook.content, 'utf8');
  if (hook.executable) {
    chmodSync(hook.path, 0o755);
  }
  return hook.path;
}

export function main(args = process.argv.slice(2), runtimePlatform = process.platform) {
  if (args.includes('--help')) {
    process.stdout.write(usage);
    return;
  }

  const dryRun = args.includes('--dry-run');
  const positionalArgs = args.filter((arg) => arg !== '--dry-run');
  const hostPlatform = hostPlatforms[runtimePlatform];
  if (!hostPlatform) {
    fail(`unsupported host platform: ${runtimePlatform}`);
  }
  if (positionalArgs.length > 1) {
    fail(`expected at most one platform argument\n${usage}`);
  }

  const targetPlatform = positionalArgs[0] ?? hostPlatform;
  if (!supportedPlatforms.has(targetPlatform)) {
    fail(`unsupported target platform: ${targetPlatform}\n${usage}`);
  }
  if (targetPlatform !== hostPlatform) {
    fail(`can only package ${targetPlatform} on a ${targetPlatform} host; current host is ${hostPlatform}`);
  }

  const scriptDir = path.dirname(fileURLToPath(import.meta.url));
  const desktopDir = path.resolve(scriptDir, '..');
  const repositoryDir = path.resolve(desktopDir, '..');
  const desktopConfigPath = path.join(desktopDir, 'src-tauri', 'tauri.conf.json');
  let desktopVersion;
  try {
    desktopVersion = readDesktopVersion(desktopConfigPath);
  } catch (error) {
    fail(error instanceof Error ? error.message : String(error));
  }
  const installedJarName = desktopCliJarName(desktopVersion);
  const sourceJar = path.join(repositoryDir, 'soloncode-cli', 'target', 'soloncode-cli.jar');
  const resourceDir = path.join(desktopDir, 'src-tauri', 'resources');
  const bundledJar = path.join(resourceDir, 'soloncode-cli.jar');
  const hookRoot = path.join(desktopDir, 'src-tauri', 'target', 'installer-hooks');
  const hookRelativePaths = {
    windows: path.join('windows', 'install-cli.nsh'),
    mac: path.join('mac', 'postinstall'),
    linux: path.join('linux', 'postinst'),
  };
  const installerHook = path.join(hookRoot, hookRelativePaths[targetPlatform]);
  const maven = runtimePlatform === 'win32' ? 'mvn.cmd' : 'mvn';
  const npx = runtimePlatform === 'win32' ? 'npx.cmd' : 'npx';

  process.stdout.write(`[package] target: ${targetPlatform}\n`);
  process.stdout.write(`[package] install jar: ~/.soloncode/bin/${installedJarName}\n`);
  process.stdout.write(`[package] installer hook: ${installerHook}\n`);
  run(maven, ['-pl', 'soloncode-cli', '-am', '-Dmaven.test.skip=true', 'clean', 'package'], repositoryDir, dryRun);

  process.stdout.write(`[package] bundle jar: ${sourceJar} -> ${bundledJar}\n`);
  if (!dryRun) {
    if (!existsSync(sourceJar) || statSync(sourceJar).size === 0) {
      fail(`current CLI JAR was not generated: ${sourceJar}`);
    }
    mkdirSync(resourceDir, { recursive: true });
    copyFileSync(sourceJar, bundledJar);
    writeInstallerHook(targetPlatform, hookRoot, installedJarName);
  }

  run(npx, ['tauri', 'build', '--config', 'src-tauri/tauri.package.conf.json'], desktopDir, dryRun);

  if (targetPlatform === 'mac') {
    const macApp = path.join(desktopDir, 'src-tauri', 'target', 'release', 'bundle', 'macos', 'soloncode-desktop.app');
    const macPkgDir = path.join(desktopDir, 'src-tauri', 'target', 'release', 'bundle', 'pkg');
    const macPkg = path.join(macPkgDir, `soloncode-desktop_${desktopVersion}_${process.arch}.pkg`);
    if (!dryRun) {
      mkdirSync(macPkgDir, { recursive: true });
    }
    run('pkgbuild', [
      '--component', macApp,
      '--install-location', '/Applications',
      '--scripts', path.dirname(installerHook),
      '--identifier', 'site.sorghum.soloncode-desktop',
      '--version', desktopVersion,
      macPkg,
    ], desktopDir, dryRun);
  }
}

const scriptPath = fileURLToPath(import.meta.url);
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(scriptPath)) {
  main();
}
