# 钉钉正文图片交付

本文件已按 Markdown 正文方案更新；共享设计和生命周期边界见 [调整方案](markdown-image-delivery-adjustment-plan.md)。

## 用法和接口

使用已启用机器人并绑定会话的企业应用，任务将 PNG/JPEG 保留到最终回复，直接引用：

```markdown
首页调整完成。

![首页截图](./screenshots/home.png)

请检查布局。
```

无需图片发布 Tool，不扫描目录，也不创建图床。

- 上传：`POST https://oapi.dingtalk.com/media/upload`，multipart `type=image`、`media`，取得 `media_id`。
- 发送：企业机器人 `oToMessages/batchSend`，`msgKey=sampleMarkdown`，字符串化 JSON `msgParam` 中的正文使用 `![说明](media_id)`。
- Markdown MediaId 用法依据：[钉钉开发者百科](https://open-dingtalk.github.io/developerpedia/docs/learn/stream/faq#3-markdown-中插入的图片无法显示问题)。上传和权限以目标企业控制台当前要求为准。

## 实现与取舍

- `最终 Markdown → AST 图片引用 → 安全快照 → 上传一次 → 原位替换 → 旧 Markdown 分片/重试`。
- 不再发送正文后的独立图片消息；无图保持旧文字入口、绑定和重试逻辑。
- 同图重复引用只上传一次；上传失败局部占位，分片重试不重新上传，不整轮重发。
- PNG/JPEG 上传名固定为 `image.png`/`image.jpg`，不透传用户文件名。
- 保留同步发送，不新增钉钉队列；一次调用内保持顺序，不承诺跨并发调用 FIFO。
- 网络边界检查中断，停止后续图片处理，不撤回已经提交的请求。
- 支持校验通过的 HTTPS 外链，客户端自行展示，服务端不下载。HTTP、含凭据、query、fragment 或无效目标降级；含签名 query 的外链暂不支持。
- 每轮最多 5 张不同本地图片，钉钉单图暂限 **1MiB**，是保守产品限制而非统一平台硬上限；不静默压缩。
- 失败说明、纯文本 fallback 和通知标题不输出本地路径或 media_id。

## 验证

本地覆盖上传结构、HTTP/业务错误、字节与安全文件名、原位顺序、重复上传缓存、失败/超限占位、外链策略、路由快照、中断与重试，以及真实最终 Markdown → RunEnd → 快照 → 钉钉适配的组合测试。

真实企业应用及手机端验收尚未完成：

1. 确认机器人权限与单聊可达，绑定当前会话。
2. 使用小于 1MiB 的截图，验证前文/图片/后文、纯图及多图。
3. 验证超限与单图失败不丢正文；取消和无图功能保持预期。
4. 检查目标客户端对 Markdown MediaId 的实际显示；Mock 成功不能替代此项。
