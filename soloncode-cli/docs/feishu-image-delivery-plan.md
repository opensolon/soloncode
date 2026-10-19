# 飞书正文图片交付

本文件已按 Markdown 正文方案更新；共享设计和生命周期边界见 [调整方案](markdown-image-delivery-adjustment-plan.md)。

## 用法

配置企业应用机器人并绑定会话，让任务把截图保留在工作区，最终回复引用图片即可：

```markdown
页面已经完成。

![首页截图](./screenshots/home.png)

上图展示了调整后的布局。
```

无需图片发布 Tool。图片在最终回复准备时读取为快照，之后修改或删除源文件不影响已准备交付。普通路径文字或代码示例不会触发上传。

## 发送链路

`最终 Markdown → AST 图片节点 → 安全快照 → 目标 FIFO → 上传 image_key → 原位替换 → post/md 分片发送`

- 不再追加独立图片消息。无图片回复走旧文字入口。
- 每个快照只上传一次，分片重试不重新上传；失败图片在原位置降级，纯文本 fallback 不泄漏图片标识。
- HTTP/HTTPS 外链不下载，校验通过后转可点击链接；含凭据、query 或 fragment 的链接保守降级。
- 保留 appId/openId 路由快照和跨实例目标 FIFO。每目标最多 64、全局最多 1024 个在途任务；图片字节每目标 32MiB、全局 128MiB，包含执行中任务。拒收抛出异常，不在队列外补发。
- 每轮最多 5 张不同本地 PNG/JPEG、总计 25MiB，单图最多 10MiB。其他读取与像素边界见共享方案。

## 配置和真实验收

应用需具备机器人、图片上传和消息发送权限，并确认发布与可用范围生效。

- 上传：https://open.feishu.cn/document/server-docs/im-v1/image/create
- 消息结构：https://open.feishu.cn/document/server-docs/im-v1/message-content-description/create_json

真实手机验收尚未完成。需验证：前文/图片/后文、纯图、多图、权限不足、超限、准备期间取消，以及排队后换绑仍使用已捕获目标与凭据。成功终态提交后，不撤回排队回复。

本地 HTTP Mock、FIFO、生命周期和真实 RunEnd 到渠道的组合测试不等于真实客户端渲染成功。
