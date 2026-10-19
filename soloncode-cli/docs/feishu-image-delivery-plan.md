# 飞书任务图片交付：实现与验收

## 目标与范围

将 Web/飞书会话中工程任务明确交付的截图，以飞书原生图片消息发送给当前绑定用户。沿用现有 App、Token 和 open_id 路由，不需要公网图床。

实现流程：

```text
任务生成 PNG/JPEG → 主代理调用 publish_image → 本轮图片快照
→ 正常顶层 RunEnd → 现有终态去重与路由快照
→ 按收件人 FIFO → 文字 → 上传图片 → 图片消息
```

不扫描目录，不解析 Markdown 本地路径，不自动外发工具读到的图片。未扩展群聊、线程、CLI/ACP 图片交付、远程 URL 下载、Web 图片画廊或重启后的补发。子代理不提供登记工具；需要主代理登记产物。

## 使用方法

先配置飞书应用并绑定会话，然后对主代理提出：

> 给当前页面截图，保存到项目内 screenshots/home.png，并调用 publish_image 把截图发给我。

截图工具仍使用原有能力。新工具登记参数示例：

```json
{"path":"screenshots/home.png","caption":"首页截图"}
```

`path` 相对本轮 CWD；登记成功仅表示待交付，正常任务结束才会发送。只在回复中写文件路径不会发送图片。图片在登记时读取为不可变内存快照，随后修改、删除源文件不改变待发内容。

## 关键实现

- `artifact/PublishImageTalent`、`PublishImageExtension`：主代理显式登记工具。
- `artifact/ImagePublishTurn`、`PublishedImage`、`SecureImageReader`：轮次隔离、校验和字节快照。
- `artifact/ImageReplyTurn`：session 唯一图片轮次入口，管理请求 token、真实 runId、终态及路由生命周期；工具不依赖 Web 层。
- `ChannelReply`：文字和图片一起交付；其他 IM 保持文字兼容，不支持图片时明确提示。
- `FeishuClient.uploadImage`：multipart 上传，返回 image_key。
- `FeishuClient.sendImageMessage`：发送 image 消息，content 为 JSON 字符串，携带稳定 uuid。
- `FeishuReplyQueue`：以 appId/openId 为键的进程共享 FIFO；一个图文任务完成再发下一个，同一目标跨工作区实例也不会交错。每目标最多64、全局最多1024个在途任务，图片字节每目标最多32MiB、全局最多128MiB，均含执行中任务；过载拒绝最新任务并记录告警，结束、异常及提交失败释放预算，空闲队列立即清除。
- 终态与路由在会话锁内领取，所有渠道发送在锁外执行；慢渠道不占用会话锁。

只有匹配本轮 runId 的正常顶层 RunEnd 可以领取图片。异常、取消、HITL 挂起或没有正常 RunEnd 的流结束不发送；旧轮回调不能清掉新轮状态。真实取消流程在持久化前原子封闭终态，阻止迟到 RunEnd 抢发成功回复。

无图回复沿用旧文字重试与 Markdown 降级。有图回复不做整包重试：文字单次分片、逐张上传并发送，失败继续后续图片，最后尽力发送安全的失败数量提示。稳定 uuid 为后续幂等重试保留；本版不自动重试图片、不保证进程重启后投递。

## 校验与运行限制

- 仅 PNG/JPEG，真实内容识别和完整解码；每张非空且不超过10MiB。
- 每轮最多5张，压缩字节总计不超过25MiB。
- 每边最多12000，总像素最多1600万（产品资源预算比飞书平台更严格）。
- caption 最多1000个 UTF-16 code unit；全进程最多两个并行图片准备任务，满载立即拒绝。
- 拒绝绝对路径、`..`、URL、挂载别名及路径所有层级的符号链接。CWD、真实文件路径必须在工作区内。
- 纯 Java 8 NIO，无 Python 依赖。读取前后验证文件身份、大小、mtime及真实路径，拒绝特殊文件和无法验证 fileKey 的文件系统。
- 使用标准 ImageIO 忽略非像素元数据，先校验尺寸再解码一次；不自行解析 PNG 块、CRC 或重复解压，允许合法元数据。

这是可信工作区下的有界读取，不是对恶意并发本地文件系统写入的原子安全保证。Java 8 跨平台 NIO 无通用非阻塞打开，不能保证抵御普通文件被恶意瞬时替换为 FIFO 的所有竞争；不应在不可信进程持续改写的目录启用此交付流程。macOS 已执行测试，Windows 尚待实机验证。队列字节预算仅约束在途图片快照，不是 JVM 总内存上限；尚未入队的各轮登记快照、解码及上传临时缓冲另占内存。

## 飞书配置与实机验收

参考官方接口：

- 上传图片：https://open.feishu.cn/document/server-docs/im-v1/image/create
- 发送消息：https://open.feishu.cn/document/server-docs/im-v1/message/create

应用需具备机器人能力、图片上传和消息发送权限，并确认发布版本与可用范围生效。权限名称、审核要求以飞书控制台当前提示为准。

实机检查：

1. 重启加载新代码，绑定测试飞书会话。
2. 请求截图并显式交付，确认手机端直接展示图片。
3. 测试纯图、带说明、多图顺序。
4. 撤销上传权限，确认文字仍可发送、图片失败有提示。
5. 取消任务，确认没有未完成截图或上一轮截图外发。
6. 发送排队期间换绑，确认在途回复仍使用入队前的目标与凭据。

本地 HTTP Mock 已覆盖 multipart、Token、content JSON 字符串、uuid及错误响应；路由、取消竞争、FIFO、图片资源与路径校验另有单测。尚未用真实飞书账号执行上述手机端验收，不能将 Mock 测试视为平台端到端成功。

## 测试命令

```bash
mvn -pl soloncode-cli -am clean test
```

针对回归：

```bash
mvn -pl soloncode-cli -am test '-Dtest=Feishu*Test,PublishedImageTest,ImagePublishTurnTest,PublishImageTalentTest,ChannelReplyTest,ImageReplyLifecycleTest,WebStreamBuilderReplyRouteTest,WebEventMapperChannelReplyTest' -Dsurefire.failIfNoSpecifiedTests=false
```
