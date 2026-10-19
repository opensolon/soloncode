package org.noear.solon.codecli.artifact;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Param;


import java.io.IOException;

/** 显式登记本轮图片；不读取回复 Markdown，也不在工具调用时发送。 */
public class PublishImageTalent extends AbsTalent {
    /** 由请求 options.toolContextPut 注入；不接受模型提供的轮次标识。 */
    public static final String CONTEXT_KEY = "__imagePublishTurnToken";
    private final HarnessEngine engine;

    public PublishImageTalent(HarnessEngine engine) {
        this.engine = engine;
    }

    @ToolMapping(name = "publish_image", description = "仅供 Web/飞书回复登记本轮工作区 PNG/JPEG 图片，不支持 CLI 图片发送。"
            + "仅在本轮正常结束时发送，调用成功不代表已发送；取消或失败则丢弃。"
            + "不会扫描 Markdown 图片链接。路径必须相对本轮 CWD，禁止绝对路径、..、URL、@挂载及所有层级链接，"
            + "真实路径必须在工作区内。每轮最多5张、压缩字节合计25MiB，每张最多10MiB，"
            + "边长最多12000且总像素最多1600万，说明最多1000字符。")
    public String publish_image(
            @Param(name = "path", description = "相对本轮 CWD 的工作区图片路径") String path,
            @Param(name = "caption", description = "可选的图片说明，最多1000字符", required = false) String caption,
            String __sessionId, String __imagePublishTurnToken) throws IOException {
        if (__sessionId == null || __sessionId.trim().isEmpty()
                || __imagePublishTurnToken == null || __imagePublishTurnToken.trim().isEmpty()) {
            throw new IOException("缺少可信会话或请求轮次标识，不能登记图片");
        }
        AgentSession session = engine.getSession(__sessionId);
        if (session == null) {
            throw new IOException("无活跃会话，不能登记图片");
        }
        final ImageReplyTurn turn;
        final ImagePublishTurn images;
        final String runId;
        synchronized (session.attrs()) {
            Object value = session.attrs().get(ImageReplyTurn.ATTR);
            if (!(value instanceof ImageReplyTurn)) {
                throw new IOException("当前会话没有图片登记轮次");
            }
            turn = (ImageReplyTurn) value;
            images = turn.getImages();
            checkCurrent(turn, __imagePublishTurnToken);
            runId = images.getRunId();
            if (runId == null || runId.trim().isEmpty()) {
                throw new IOException("当前图片登记轮次尚未绑定 runId");
            }
        }
        // 读取和解码不占用会话锁，取消/换轮可立即关闭登记簿。
        PublishedImage image = prepare(images, path, caption);
        synchronized (session.attrs()) {
            checkCurrent(turn, __imagePublishTurnToken);
            if (!runId.equals(images.getRunId())) {
                throw new IOException("图片登记轮次已改变");
            }
            images.commit(image);
        }
        ONode result = new ONode();
        result.set("registered", true);
        result.set("uuid", image.getUuid());
        result.set("filename", image.getFilename());
        result.set("message", "图片已登记，仅在本轮正常结束时发送");
        return result.toJson();
    }
    PublishedImage prepare(ImagePublishTurn images, String path, String caption) throws IOException {
        return images.prepare(path, caption);
    }

    private static void checkCurrent(ImageReplyTurn turn, String token) throws IOException {
        if (!turn.isCurrent() || !token.equals(turn.getToken())) {
            throw new IOException("图片登记请求不属于当前轮次");
        }
    }
}
