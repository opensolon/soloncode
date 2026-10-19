# Windows PowerShell 2.0～5.1 安装与启动兼容

## 实现结构

- `soloncode-cli/release/install.ps1` 是唯一安装实现，生成一份公共 `soloncode.ps1`；安装与启动脚本均使用 PS2 可解析语法。
- 公共流程维护参数处理、端口判断、实例校验、token、工作区交接、浏览器回退和 Java 启动。
- JSON：PS3+ 使用内置 cmdlet；PS2 加载 `System.Web.Extensions` 并使用无自定义类型解析器的 `JavaScriptSerializer`。安装前检查组件，启动时仅在复用实例需要 JSON 时初始化。输入限制为 65536 字符、32 层嵌套。
- TCP：通过 .NET 2.0 已有的 `IPGlobalProperties.GetActiveTcpListeners()` 查询本机 IPv4/IPv6 监听表，避免 Windows 空闲端口连接拒绝延迟超过 500ms 导致误挡启动。按端口保守匹配所有本机绑定地址；仅无监听时允许启动 Java，查询失败必须报错。发现监听后仍验证实例及交接，HTTP 超时和无法验证实例均报错，不回退启动。监听表是快照，不消除并发启动竞态，最终仍由 Java 绑定端口判定。
- `install-ps2.ps1` 为薄入口，文件调用同进程转交公共脚本；IEX 调用显式依赖安装来源环境变量，读取公共脚本后 IEX，以保留原执行方式，并恢复来源环境变量。禁止从不明确的当前目录猜测公共脚本。
- 发布清单同时包含两个入口。生成 PS1 使用 UTF-8 BOM / CRLF，BAT 使用 ASCII / CRLF，Git Bash 使用 UTF-8 无 BOM / LF。

## 保留与边界

已有 AGENTS.md 原位保留；仅在根目录缺失时迁移旧 bin/ 文件，否则才复制发行包默认文件，bin/ 批量复制排除 AGENTS.md。保留用户额外 skills/commands、发行包同名更新、PATH 配置、Java 编码参数和 JDK 21 判断、退出码，以及 CMD/Git Bash 转交结构。修改安装目录前检查源 JAR，并拒绝源目标同目录。启动器直接收集普通 `$args`，不使用高级参数绑定，避免吞掉 CLI 的 `-Verbose`、`-Debug`、`-ErrorAction` 等参数。

不改变端口来源规则、帮助参数集合、info 2 秒 / attach 5 秒超时、服务端协议或并发启动行为。在线 setup 的 TLS/证书与 tar 仍依赖系统能力；脚本兼容不扩展为完整产品的旧操作系统支持承诺。安装复制及卸载均非事务操作，失败可能已经产生部分变化，不能声称自动回滚。

### PATH 与卸载重试契约

- 安装从当前用户注册表读取 PATH 原值（`DoNotExpandEnvironmentNames`）及原类型，只接受 `String` / `ExpandString`；缺失值新建为 `String`。已有完整 bin 项按忽略大小写比较，存在则不写入；否则仅追加，保留变量占位符、空项、顺序和其他文本。引号、空格、尾反斜杠或近似目录不等于精确项。读值、读类型、写入或 Flush 失败仍关闭句柄，异常不吞掉。PATH 检查不构成安装复制前的预检，失败可能已有安装文件变化。
- 卸载确认后再次检查目录安全性，再打开用户环境键、读取原始 PATH、校验类型并预读 HOME；这些动作必须早于删除目录。预读/类型错误不得删除；删除失败或残留不得继续写 PATH。预读在删除前不意味着全流程原子化，也不消除并发目录变更风险。
- 安装目录缺失不是提前成功退出：仍要求确认并重新检查，跳过删除，但继续移除精确匹配的 User PATH 项及指向该安装目录的 User `SOLONCODE_HOME`。不匹配 HOME、其他 PATH 项、Machine 环境和共享安装保持不变。目录已删除但环境写入失败时可重试；已清理 PATH 再次运行不得重复写入。

## 本地验证

```bash
python3 -B -m unittest discover -s soloncode-cli/release/tests -v
mvn test
(cd soloncode-desktop && npm test)
git diff --check
```

兼容测试使用 Python 标准库。默认查找 `powershell`，也可通过 `SOLONCODE_TEST_POWERSHELL` 指定真实宿主可执行文件。未找到时运行测试明确跳过；不自动以 `pwsh` 代替 Windows PowerShell。不覆盖或伪造 `PSVersionTable`。

静态契约覆盖安装 AGENTS 不覆盖、LiteralPath、setup 环境恢复与 PS2 UTF-8 读法、tar 预检/退出码、HTTPS 重定向限制、安装 PATH 原值/原类型、卸载预读在删除之前、缺目录无提前退出且可清理精确环境项，以及用户范围和失败状态。本次 PATH 内存探针、删除片段和原失败 catch 使用唯一且正向的抽取边界，没有宿主也检查这些边界与禁止操作，避免运行测试跳过掩盖过期锚点；不声称所有既有启动器抽取都已改用此机制。

可选运行测试抽取生产源码的有限区域，不复刻被测算法：启动器函数/生成/普通参数分派；临时 stub 验证薄入口与 setup 成功/异常 finally、子作用域和 PATH 保留/去重；临时 AGENTS 文件验证保留/迁移/默认复制；Windows 原生无害 `.cmd` 验证 tar 非零退出码；真实下载函数验证 HTTP 在发请求前被拒绝。

安装内存 adapter 只抽取原始 PATH 读取开始至原 `finally/Close` 结束的代码，排除注册表打开/创建。卸载分别抽取 PATH 预读计算和写入/关闭片段，刻意跳过中间的真实 HOME 读取和删除；两侧用测试 `try` 外壳连接，保留源码算法及 finally。`memory_region` 检查并拒绝注册表打开/创建、环境 API、`$env:`、常见文件修改/执行命令和 IO 文件类型引用；该有限检查不是 PowerShell 安全解析器，源码变化仍须审阅。运行用例覆盖两种字符串类型、缺失/空值、原始占位符、精确项去重/过滤、重复目标、空项/尾分号保留，以及读值/读类型/写入/Flush 失败关闭句柄；另以真实形态的数值、数组验证非字符串类型拒写。adapter 返回值避免数组被枚举，重试使用新 adapter 承接旧内存值，不复用已关闭句柄。

删除阶段是单独的有限源码片段，只有在 `Remove-Item` / `Test-Path` 无害命令 stub 已定义后才运行；不加载真实目录安全函数或完整卸载入口。与内存 PATH 片段组合验证预读失败零删除、删除失败零 PATH 写入、缺目录零删除且 PATH 写失败后可重试并幂等。独立失败探针保留原 catch，验证退出码 1 和非回滚提示。所有探针文件均在临时目录；不执行真实安装/卸载入口，不打开注册表，不读取真实 User HOME 环境值，不修改真实用户持久 PATH。

HTTPS 正向下载、证书链与 HTTPS→HTTP 跳转尚无实机运行覆盖；卸载 HOME 精确清理条件、取消确认和目录安全检查调用顺序仅有静态契约，不声称已动态执行 HOME 清理、目录安全算法或完整卸载。祖先目录、重解析点和递归检查仍须实机验证。内存 adapter 不能验证真实注册表访问权限、创建键行为、实际类型持久化或真实句柄重新打开。上述测试及 loopback HTTP/TCP 用例不能替代完整安装与三终端端到端验证。

### 本次复跑记录

- Release 全量发现：48 个测试方法，21 通过、27 因无真实 PowerShell 宿主跳过，0 失败/错误；相较修改前新增 8 个测试方法。统计不展开 `subTest`。
- 另外在 Python 中构造并检查 58 份 PATH/删除 stub 探针正文，未发现格式化错误、禁止环境 API 或明显定界符数量失衡；没有执行 PowerShell，不构成语法解析或运行通过。
- Desktop `npm test`：71 通过，0 失败、0 跳过。
- 根 `mvn test`：CLI 1055 项，1052 通过、1 失败、0 错误、2 跳过；后续模块被中断。失败为 `AgentSettingsControllerTest.builtinStillReadableAfterOverrideFileExists:329`，期望 HTTP 200、实际 400。
- `mvn -fae test` 完整复跑并继续可运行模块，同样复现 CLI 失败；SDK 报告 399 项，398 通过、0 失败、1 错误、0 跳过。错误为 `PersistentStdioClientTest.reactiveCompositionCanStartTheNextTurnAfterCompletion`：`A response is still active; consume or cancel it before starting another turn`。CLI/SDK 报告合计 1454 项，1450 通过、1 失败、1 错误、2 跳过；依赖失败模块的后续模块仍可能被 Maven 跳过。此次不放宽断言、不修改范围外代码，不能宣称 Maven 全绿。
- `git diff --check` 通过。本次仅编辑本测试文件和本文档，保留工作区原有其他改动。

## 空闲端口误判回归

新增 `soloncode-cli/release/tests/test_launcher_port.py`，针对本次端口修复独立验证，不代表恢复上述历史测试集。静态契约覆盖监听表查询、禁止短连接探测、IPv4/IPv6 不过滤、查询失败拒绝启动及保留实例验证；可选真实 PowerShell 探针覆盖 IPv4/IPv6 监听、空闲端口和实例验证失败。仅抽取生产函数，不执行安装器、Java 或浏览器。没有 Windows PowerShell 时运行探针明确跳过，不能视作 Windows 实机通过。

## Windows 发布前验收

当前开发环境没有 PowerShell，尚未完成 Windows 运行矩阵。发布前必须分别验证真实 PS2/CLR2、PS3、PS4、PS5.0、PS5.1：

- 运行上述可选 PowerShell 用例；记录实际宿主版本，不以静态检查替代。
- 在隔离用户或虚拟机中完成首次安装、重复安装、旧 AGENTS.md 迁移、用户额外文件保留和 PATH 验证。
- PowerShell、CMD、Git Bash 验证参数顺序、中文/空格/引号/尾部反斜杠、当前目录和 Java 退出码。
- 使用真实服务验证空闲启动、已有实例打开当前目录，以及其他服务、超时、无效 token 时不误启 Java。
- PS2 缺组件必须在修改安装目录前失败；必要时安装适配系统和 CLR 的 .NET 组件后重试。
- 单独核实 JDK、系统组件和浏览器支持。脚本以 PS2 为兼容目标，不构成 Windows Server 2008 产品认证。

已实现兼容路径不等于已通过以上实机验收；待验证项必须保持明确标记。
