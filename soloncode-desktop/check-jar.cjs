const fs = require('fs');
const path = require('path');

// beforeBuildCommand 的 cwd 是 soloncode-desktop/（package.json 所在目录）
const jarPath = path.resolve(process.cwd(), '../soloncode-cli/target/soloncode-cli.jar');

if (!fs.existsSync(jarPath)) {
  console.error('');
  console.error('ERROR: soloncode-cli.jar not found');
  console.error('  Expected: soloncode-cli/target/soloncode-cli.jar');
  console.error('  Run: cd soloncode-cli && mvn clean package -DskipTests');
  console.error('');
  process.exit(1);
}

console.log('[check] soloncode-cli.jar found');
