import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const scriptPath = fileURLToPath(new URL('../scripts/package-desktop.mjs', import.meta.url));
const desktopDir = fileURLToPath(new URL('..', import.meta.url));
const nativePlatform = {
  win32: 'windows',
  darwin: 'mac',
  linux: 'linux',
}[process.platform];

test('package script exposes the supported native platforms', () => {
  const result = spawnSync(process.execPath, [scriptPath, '--help'], {
    encoding: 'utf8',
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /windows\|mac\|linux/);
  assert.match(result.stdout, /--dry-run/);
});

test('dry run builds the current jar before the native desktop package', () => {
  assert.ok(nativePlatform, `Unsupported test host: ${process.platform}`);

  const result = spawnSync(process.execPath, [scriptPath, nativePlatform, '--dry-run'], {
    encoding: 'utf8',
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, new RegExp(`target: ${nativePlatform}`));
  assert.match(result.stdout, /mvn(?:\.cmd)? .*clean package/);
  assert.match(result.stdout, /soloncode-cli\.jar/);
  assert.match(result.stdout, /tauri build/);
});

test('rejects packaging for a platform different from the host', () => {
  assert.ok(nativePlatform, `Unsupported test host: ${process.platform}`);
  const foreignPlatform = nativePlatform === 'linux' ? 'windows' : 'linux';

  const result = spawnSync(process.execPath, [scriptPath, foreignPlatform, '--dry-run'], {
    encoding: 'utf8',
  });

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /can only package .* on .* host/i);
});

test('npm package command forwards arguments to the packaging script', () => {
  const command = process.platform === 'win32'
    ? (process.env.ComSpec ?? 'cmd.exe')
    : 'npm';
  const commandArgs = process.platform === 'win32'
    ? ['/d', '/s', '/c', 'npm.cmd run package -- --help']
    : ['run', 'package', '--', '--help'];
  const result = spawnSync(command, commandArgs, {
    cwd: desktopDir,
    encoding: 'utf8',
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /windows\|mac\|linux/);
});

test('npm package plan command prints steps without starting the build', () => {
  assert.ok(nativePlatform, `Unsupported test host: ${process.platform}`);
  const command = process.platform === 'win32'
    ? (process.env.ComSpec ?? 'cmd.exe')
    : 'npm';
  const packageCommand = `npm.cmd run package:plan -- ${nativePlatform}`;
  const commandArgs = process.platform === 'win32'
    ? ['/d', '/s', '/c', packageCommand]
    : ['run', 'package:plan', '--', nativePlatform];
  const result = spawnSync(command, commandArgs, {
    cwd: desktopDir,
    encoding: 'utf8',
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /clean package/);
  assert.match(result.stdout, /tauri build/);
  assert.doesNotMatch(result.stdout, /BUILD SUCCESS/);
});
