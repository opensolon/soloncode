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

test('enables explicit annotation processing only on JDKs that disable it by default', () => {
  assert.equal(desktopPackage.parseJavaMajor('1.8.0_442'), 8);
  assert.equal(desktopPackage.parseJavaMajor('17.0.12'), 17);
  assert.equal(desktopPackage.parseJavaMajor('openjdk version "25.0.2"'), 25);
  assert.deepEqual(desktopPackage.mavenCompilerProperties(8), []);
  assert.deepEqual(desktopPackage.mavenCompilerProperties(17), []);
  assert.deepEqual(desktopPackage.mavenCompilerProperties(22), []);
  assert.deepEqual(desktopPackage.mavenCompilerProperties(23), ['-Dmaven.compiler.proc=full']);
  assert.deepEqual(desktopPackage.mavenCompilerProperties(25), ['-Dmaven.compiler.proc=full']);
});

test('dry run plans the versioned CLI install destination', () => {
  assert.ok(nativePlatform, `Unsupported test host: ${process.platform}`);

  const result = spawnSync(process.execPath, [scriptPath, nativePlatform, '--dry-run'], {
    encoding: 'utf8',
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /~\/.soloncode\/bin\/soloncode-cli_26\.9\.26\.jar/);
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

test('renders an MSI component that installs the versioned CLI jar in the user profile', () => {
  assert.equal(typeof desktopPackage.renderWixFragment, 'function');

  const wix = desktopPackage.renderWixFragment(
    'soloncode-cli_26.8.11.jar',
    'D:\\build\\resources\\soloncode-cli.jar',
  );

  assert.match(wix, /Property="SOLONCODE_CLI_DIR"/);
  assert.match(wix, /Value="\[%USERPROFILE\]\\\.soloncode\\bin"/);
  assert.match(wix, /Component Id="SolonCodeVersionedCliJar" Guid="[0-9A-F-]{36}" Permanent="yes"/);
  assert.doesNotMatch(wix, /Component Id="SolonCodeVersionedCliJar" Guid="\*"/);
  assert.match(wix, /RegistryValue Root="HKCU"[^>]+KeyPath="yes"/);
  assert.doesNotMatch(wix, /<File[^>]+KeyPath="yes"/);
  assert.match(wix, /Name="soloncode-cli_26\.8\.11\.jar"/);
  assert.match(wix, /Source="D:\\build\\resources\\soloncode-cli\.jar"/);

  const escapedPath = desktopPackage.renderWixFragment(
    'soloncode-cli_26.8.11.jar',
    'D:\\build & release\\soloncode-cli.jar',
  );
  assert.match(escapedPath, /Source="D:\\build &amp; release\\soloncode-cli\.jar"/);

  const nextVersion = desktopPackage.renderWixFragment(
    'soloncode-cli_26.8.12.jar',
    'D:\\build\\resources\\soloncode-cli.jar',
  );
  const componentGuid = wix.match(/Component Id="SolonCodeVersionedCliJar" Guid="([0-9A-F-]{36})"/)?.[1];
  const nextComponentGuid = nextVersion.match(/Component Id="SolonCodeVersionedCliJar" Guid="([0-9A-F-]{36})"/)?.[1];
  assert.ok(componentGuid);
  assert.ok(nextComponentGuid);
  assert.notEqual(componentGuid, nextComponentGuid);
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

test('writes the Windows MSI fragment next to the NSIS hook', () => {
  assert.equal(typeof desktopPackage.writeWixFragment, 'function');
  const root = mkdtempSync(path.join(tmpdir(), 'soloncode-wix-fragment-'));

  try {
    const fragment = desktopPackage.writeWixFragment(
      root,
      'soloncode-cli_26.8.11.jar',
      'D:\\build\\resources\\soloncode-cli.jar',
    );

    assert.equal(path.relative(root, fragment), path.join('windows', 'install-cli.wxs'));
    assert.match(readFileSync(fragment, 'utf8'), /SolonCodeVersionedCliJar/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('native bundle configs register hook-capable installer formats', () => {
  const windows = JSON.parse(readFileSync(path.join(tauriDir, 'tauri.windows.conf.json'), 'utf8'));
  const mac = JSON.parse(readFileSync(path.join(tauriDir, 'tauri.macos.conf.json'), 'utf8'));
  const linux = JSON.parse(readFileSync(path.join(tauriDir, 'tauri.linux.conf.json'), 'utf8'));

  assert.deepEqual(windows.bundle.targets, ['nsis', 'msi']);
  assert.equal(windows.bundle.windows.nsis.installMode, 'currentUser');
  assert.equal(windows.bundle.windows.nsis.installerHooks, 'target/installer-hooks/windows/install-cli.nsh');
  assert.equal(windows.bundle.windows.wix.template, 'wix/main.wxs');
  assert.deepEqual(windows.bundle.windows.wix.fragmentPaths, ['target/installer-hooks/windows/install-cli.wxs']);
  assert.deepEqual(windows.bundle.windows.wix.componentRefs, ['SolonCodeVersionedCliJar']);
  assert.deepEqual(mac.bundle.targets, ['app', 'dmg']);
  assert.ok(linux.bundle.targets.includes('deb'));
  assert.equal(linux.bundle.linux.deb.postInstallScript, 'target/installer-hooks/linux/postinst');
});

test('Windows MSI template uses a per-user installation scope', () => {
  const template = readFileSync(path.join(tauriDir, 'wix', 'main.wxs'), 'utf8');

  assert.match(template, /InstallScope="perUser"/);
  assert.match(template, /Property="INSTALLDIR"/);
  assert.match(template, /Value="\[LocalAppDataFolder\]Programs&#92;\{\{product_name\}\}"/);
  assert.equal((template.match(/<Custom Action="SetPerUserInstallDir" Before="CostFinalize">NOT INSTALLDIR<\/Custom>/g) ?? []).length, 2);
  assert.doesNotMatch(template, /Programs\\\{\{product_name\}\}/);
  assert.doesNotMatch(template, /InstallScope="perMachine"/);
  assert.doesNotMatch(template, /PlatformProgramFilesFolder/);
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
  assert.match(mac.stdout, /pkgbuild .*soloncode-desktop_26\.9\.26_.*\.pkg/);
});

test('Windows dry run prepares both NSIS and MSI installer inputs', () => {
  const moduleUrl = new URL('../scripts/package-desktop.mjs', import.meta.url).href;
  const windowsCode = `const m = await import(${JSON.stringify(moduleUrl)}); m.main(['windows', '--dry-run'], 'win32');`;
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', windowsCode], {
    cwd: desktopDir,
    encoding: 'utf8',
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /install-cli\.nsh/);
  assert.match(result.stdout, /install-cli\.wxs/);
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
