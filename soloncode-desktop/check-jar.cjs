const fs = require('fs');
const path = require('path');

// beforeBuildCommand 的 cwd 是 soloncode-desktop/（package.json 所在目录）
const srcJar = path.resolve(process.cwd(), '../soloncode-cli/target/soloncode-cli.jar');

if (!fs.existsSync(srcJar)) {
  console.error('');
  console.error('ERROR: soloncode-cli.jar not found');
  console.error('  Expected: soloncode-cli/target/soloncode-cli.jar');
  console.error('  Run: cd soloncode-cli && mvn clean package -DskipTests');
  console.error('');
  process.exit(1);
}

// 将 CLI 构建产物直接复制到 src-tauri/ 下（零 _up_ 路径）
const tauriDir = path.resolve(process.cwd(), 'src-tauri');
const targetDir = path.join(tauriDir, 'target');
const releaseDir = path.join(tauriDir, 'release');
const buildDir = path.join(tauriDir, 'build');

// 复制 target/soloncode-cli.jar
fs.mkdirSync(targetDir, { recursive: true });
fs.copyFileSync(srcJar, path.join(targetDir, 'soloncode-cli.jar'));
console.log('[check] Copied soloncode-cli.jar -> src-tauri/target/');

// 复制 release/ 目录
const srcRelease = path.resolve(process.cwd(), '../soloncode-cli/release');
if (fs.existsSync(srcRelease)) {
  fs.cpSync(srcRelease, releaseDir, { recursive: true, force: true });
  console.log('[check] Copied release/ -> src-tauri/release/');
}

// 复制 build/install-cli.*
const srcBuild = path.resolve(process.cwd(), 'build');
if (fs.existsSync(srcBuild)) {
  fs.mkdirSync(buildDir, { recursive: true });
  for (const f of fs.readdirSync(srcBuild)) {
    if (f.startsWith('install-cli.')) {
      fs.copyFileSync(path.join(srcBuild, f), path.join(buildDir, f));
    }
  }
  console.log('[check] Copied build/install-cli.* -> src-tauri/build/');
}

console.log('[check] soloncode-cli.jar found');
