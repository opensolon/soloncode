#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, statSync } from 'node:fs';
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

const args = process.argv.slice(2);
if (args.includes('--help')) {
  process.stdout.write(usage);
} else {
  const dryRun = args.includes('--dry-run');
  const positionalArgs = args.filter((arg) => arg !== '--dry-run');
  const hostPlatform = hostPlatforms[process.platform];
  if (!hostPlatform) {
    fail(`unsupported host platform: ${process.platform}`);
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
  const sourceJar = path.join(repositoryDir, 'soloncode-cli', 'target', 'soloncode-cli.jar');
  const resourceDir = path.join(desktopDir, 'src-tauri', 'resources');
  const bundledJar = path.join(resourceDir, 'soloncode-cli.jar');
  const maven = process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
  const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';

  process.stdout.write(`[package] target: ${targetPlatform}\n`);
  run(maven, ['-pl', 'soloncode-cli', '-am', '-Dmaven.test.skip=true', 'clean', 'package'], repositoryDir, dryRun);

  process.stdout.write(`[package] bundle jar: ${sourceJar} -> ${bundledJar}\n`);
  if (!dryRun) {
    if (!existsSync(sourceJar) || statSync(sourceJar).size === 0) {
      fail(`current CLI JAR was not generated: ${sourceJar}`);
    }
    mkdirSync(resourceDir, { recursive: true });
    copyFileSync(sourceJar, bundledJar);
  }

  run(npx, ['tauri', 'build', '--config', 'src-tauri/tauri.package.conf.json'], desktopDir, dryRun);
}
