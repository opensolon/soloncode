import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import * as desktopPackage from '../scripts/package-desktop.mjs';

const scriptPath = fileURLToPath(new URL('../scripts/package-desktop.mjs', import.meta.url));
const desktopDir = fileURLToPath(new URL('..', import.meta.url));
const tauriDir = fileURLToPath(new URL('../src-tauri/', import.meta.url));
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

test('dry run plans the versioned CLI install destination', () => {
  assert.ok(nativePlatform, `Unsupported test host: ${process.platform}`);

  const result = spawnSync(process.execPath, [scriptPath, nativePlatform, '--dry-run'], {
    encoding: 'utf8',
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /~\/.soloncode\/bin\/soloncode-cli_26\.8\.11\.jar/);
});

test('renders strict native installer hooks for the versioned CLI jar', () => {
  assert.equal(typeof desktopPackage.renderNsisHook, 'function');
  assert.equal(typeof desktopPackage.renderMacPostInstall, 'function');
  assert.equal(typeof desktopPackage.renderLinuxPostInstall, 'function');

  const jarName = 'soloncode-cli_26.8.11.jar';
  const nsis = desktopPackage.renderNsisHook(jarName);
  assert.match(nsis, /NSIS_HOOK_POSTINSTALL/);
  assert.match(nsis, /\$PROFILE\\\.soloncode\\bin/);
  assert.match(nsis, /soloncode-cli_26\.8\.11\.jar/);
  assert.match(nsis, /Abort/);

  const mac = desktopPackage.renderMacPostInstall(jarName);
  assert.match(mac, /\/dev\/console/);
  assert.match(mac, /\/Applications\/soloncode-desktop\.app\/Contents\/Resources\/soloncode-cli\.jar/);
  assert.match(mac, /\.soloncode\/bin\/soloncode-cli_26\.8\.11\.jar/);
  assert.match(mac, /exit 1/);

  const linux = desktopPackage.renderLinuxPostInstall(jarName);
  assert.match(linux, /SUDO_USER/);
  assert.match(linux, /PKEXEC_UID/);
  assert.match(linux, /\/usr\/lib\/soloncode-desktop\/soloncode-cli\.jar/);
  assert.match(linux, /\.soloncode\/bin\/soloncode-cli_26\.8\.11\.jar/);
  assert.match(linux, /exit 1/);
});

test('writes the selected native installer hook to the generated target directory', () => {
  assert.equal(typeof desktopPackage.writeInstallerHook, 'function');
  const root = mkdtempSync(path.join(tmpdir(), 'soloncode-installer-hooks-'));
  const jarName = 'soloncode-cli_26.8.11.jar';

  try {
    const windowsHook = desktopPackage.writeInstallerHook('windows', root, jarName);
    const macHook = desktopPackage.writeInstallerHook('mac', root, jarName);
    const linuxHook = desktopPackage.writeInstallerHook('linux', root, jarName);

    assert.equal(path.relative(root, windowsHook), path.join('windows', 'install-cli.nsh'));
    assert.equal(path.relative(root, macHook), path.join('mac', 'postinstall'));
    assert.equal(path.relative(root, linuxHook), path.join('linux', 'postinst'));
    assert.match(readFileSync(windowsHook, 'utf8'), /NSIS_HOOK_POSTINSTALL/);
    assert.match(readFileSync(macHook, 'utf8'), /soloncode-cli_26\.8\.11\.jar/);
    assert.match(readFileSync(linuxHook, 'utf8'), /soloncode-cli_26\.8\.11\.jar/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('native bundle configs register hook-capable installer formats', () => {
  const windows = JSON.parse(readFileSync(path.join(tauriDir, 'tauri.windows.conf.json'), 'utf8'));
  const mac = JSON.parse(readFileSync(path.join(tauriDir, 'tauri.macos.conf.json'), 'utf8'));
  const linux = JSON.parse(readFileSync(path.join(tauriDir, 'tauri.linux.conf.json'), 'utf8'));

  assert.deepEqual(windows.bundle.targets, ['nsis']);
  assert.equal(windows.bundle.windows.nsis.installMode, 'currentUser');
  assert.equal(windows.bundle.windows.nsis.installerHooks, 'target/installer-hooks/windows/install-cli.nsh');
  assert.deepEqual(mac.bundle.targets, ['app', 'dmg']);
  assert.ok(linux.bundle.targets.includes('deb'));
  assert.equal(linux.bundle.linux.deb.postInstallScript, 'target/installer-hooks/linux/postinst');
});

test('dry run prepares the native hook and adds a macOS PKG installer', () => {
  assert.ok(nativePlatform, `Unsupported test host: ${process.platform}`);
  const native = spawnSync(process.execPath, [scriptPath, nativePlatform, '--dry-run'], {
    encoding: 'utf8',
  });
  assert.equal(native.status, 0, native.stderr);
  assert.match(native.stdout, new RegExp(`installer-hooks[\\\\/]${nativePlatform}[\\\\/]`));

  const moduleUrl = new URL('../scripts/package-desktop.mjs', import.meta.url).href;
  const macCode = `const m = await import(${JSON.stringify(moduleUrl)}); m.main(['mac', '--dry-run'], 'darwin');`;
  const mac = spawnSync(process.execPath, ['--input-type=module', '-e', macCode], {
    cwd: desktopDir,
    encoding: 'utf8',
  });
  assert.equal(mac.status, 0, mac.stderr);
  assert.match(mac.stdout, /pkgbuild .*soloncode-desktop_26\.8\.11_.*\.pkg/);
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
