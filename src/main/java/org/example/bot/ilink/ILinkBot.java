package org.example.bot.ilink;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.example.bot.model.BotMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 微信 iLink 机器人的简化门面。
 */
public class ILinkBot {

    private final ILinkClient client;
    private LoginContext loginContext;
    private final ConcurrentLinkedQueue<WeixinMessage> rawQueue;

    private ILinkBot(ILinkClient client, ConcurrentLinkedQueue<WeixinMessage> rawQueue) {
        this.client = client;
        this.rawQueue = rawQueue;
    }

    public static ILinkBot create() {
        ConcurrentLinkedQueue<WeixinMessage> queue = new ConcurrentLinkedQueue<>();

        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000).readTimeoutMs(35000).writeTimeoutMs(35000)
                .httpMaxRetries(3).heartbeatEnabled(true).heartbeatIntervalMs(30000)
                .build();

        ILinkClient client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override public void onLoginSuccess(LoginContext ctx) {
                        System.out.println("[iLink] ✅ 登录成功！Bot ID: " + ctx.getBotId());
                    }
                    @Override public void onLoginFailure(Throwable e) {
                        System.err.println("[iLink] ❌ 登录失败: " + e.getMessage());
                    }
                })
                .onMessage(msgs -> {
                    System.out.println("[iLink] 📩 收到 " + msgs.size() + " 条消息 → 入队");
                    queue.addAll(msgs);
                })
                .build();

        return new ILinkBot(client, queue);
    }

    public void login() {
        for (int attempt = 1; attempt <= 10; attempt++) {
            System.out.println("[iLink] 正在获取登录二维码...（第 " + attempt + " 次）");
            String qrContent = client.executeLogin();
            System.out.println("请用微信扫描下方二维码登录：\n");
            System.out.println(qrContent);
            try {
                this.loginContext = client.getLoginFuture().get();
                System.out.println("[iLink] 登录流程完成！");
                return;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String msg = (cause.getMessage() != null ? cause.getMessage() : "")
                    + (e.getMessage() != null ? e.getMessage() : "");
                if (msg.contains("expired") || msg.contains("过期") || msg.contains("SessionExpired")) {
                    System.out.println("[iLink] ⚠ 二维码已过期，重新生成...");
                } else {
                    throw new RuntimeException("登录失败: " + msg, e);
                }
            }
        }
        throw new RuntimeException("登录失败：二维码过期次数超过 10 次");
    }

    /**
     * 从消息队列中拉取并转换为 {@link BotMessage}。
     *
     * <p>优先从 {@link OnMessageListener} 推送的内部队列取消息（无竞争），
     * 队列为空时才调用 {@code getUpdates()} 兜底。
     */
    public List<BotMessage> pollMessages() {
        // ① 优先从 Listener 推送的内部队列取消息
        List<WeixinMessage> rawMessages = new ArrayList<>();
        WeixinMessage msg;
        while ((msg = rawQueue.poll()) != null) {
            rawMessages.add(msg);
        }

        // ② 队列为空时用 getUpdates 兜底
        if (rawMessages.isEmpty()) {
            for (int retry = 0; retry < 2; retry++) {
                try {
                    List<WeixinMessage> fallback = client.getUpdates();
                    if (fallback != null && !fallback.isEmpty()) {
                        System.out.println("[iLink] 🔄 getUpdates 兜底收到 " + fallback.size() + " 条");
                        rawMessages = fallback;
                    }
                    break;
                } catch (IOException e) {
                    System.err.println("[iLink] ⚠ 拉取失败(重试" + (retry+1) + "): " + e.getMessage());
                    if (retry < 1) try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
        }

        if (rawMessages.isEmpty()) return List.of();

        List<BotMessage> result = new ArrayList<>();
        for (WeixinMessage wm : rawMessages) {
            String userId = wm.getFrom_user_id();
            if (userId == null) continue;
            String text = extractText(wm);
            byte[] image = extractImage(wm);
            VoiceData vd = extractVoiceData(wm);
            FileData fd = extractFileData(wm);

            if (fd != null) result.add(BotMessage.file(userId, fd.bytes(), fd.name()));
            else if (vd != null && (vd.text() != null || vd.audio() != null))
                result.add(BotMessage.voice(userId, vd.audio() != null ? vd.audio() : new byte[0], vd.text()));
            else if (image != null) result.add(BotMessage.image(userId, image, text));
            else if (!text.isEmpty()) result.add(BotMessage.text(userId, text));
        }
        return result;
    }

    // ---- 发送 ----
    public void sendText(String userId, String text) {
        try { client.sendText(userId, text); }
        catch (IOException e) { System.err.println("[iLink] ⚠ 发送失败: " + e.getMessage()); }
    }

    public void sendTextWithTyping(String userId, String text, long typingMillis) {
        try { client.sendTextWithTyping(userId, text, typingMillis); }
        catch (IOException e) { System.err.println("[iLink] ⚠ 发送失败: " + e.getMessage()); }
    }

    public void sendImage(String userId, byte[] imageBytes, String fileName, String caption) {
        try { client.sendImage(userId, imageBytes, fileName, caption != null ? caption : ""); }
        catch (IOException e) { System.err.println("[iLink] ⚠ 发送失败: " + e.getMessage()); }
    }

    public void sendFile(String userId, byte[] fileBytes, String fileName, String caption) {
        try { client.sendFile(userId, fileBytes, fileName, caption != null ? caption : ""); }
        catch (IOException e) { System.err.println("[iLink] ⚠ 发送失败: " + e.getMessage()); }
    }

    public void sendVoiceWithText(String userId, byte[] audioBytes, String fileName, String text) {
        try { client.sendFile(userId, audioBytes, fileName, ""); }
        catch (IOException e) { System.err.println("[iLink] ⚠ 发送失败: " + e.getMessage()); }
        if (text != null && !text.isBlank()) sendText(userId, "🎤 " + text);
    }

    public String getBotId() { return loginContext != null ? loginContext.getBotId() : null; }

    public void close() {
        System.out.println("[iLink] 正在关闭...");
        client.close();
        System.out.println("[iLink] 已关闭。");
    }

    // ---- 提取 ----
    private static String extractText(WeixinMessage msg) {
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (MessageItem item : items) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(item.getText_item().getText());
            }
        }
        return sb.toString();
    }

    private byte[] extractImage(WeixinMessage msg) {
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return null;
        for (MessageItem item : items) {
            if (item.getImage_item() != null && item.getImage_item().getMedia() != null) {
                try { return client.downloadImageFromMessageItem(item); }
                catch (IOException e) { System.err.println("[iLink] ⚠ 下载图片失败: " + e.getMessage()); }
            }
        }
        return null;
    }

    private VoiceData extractVoiceData(WeixinMessage msg) {
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return null;
        for (MessageItem item : items) {
            if (item.getVoice_item() != null) {
                String text = item.getVoice_item().getText();
                byte[] audio = null;
                if (item.getVoice_item().getMedia() != null) {
                    try { audio = client.downloadVoiceFromMessageItem(item); }
                    catch (IOException e) { System.err.println("[iLink] ⚠ 下载语音失败: " + e.getMessage()); }
                }
                return new VoiceData(audio, text);
            }
        }
        return null;
    }

    private record VoiceData(byte[] audio, String text) {}

    private FileData extractFileData(WeixinMessage msg) {
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return null;
        for (MessageItem item : items) {
            if (item.getFile_item() != null && item.getFile_item().getMedia() != null) {
                try {
                    byte[] data = client.downloadFileFromMessageItem(item);
                    String name = item.getFile_item().getFile_name();
                    return new FileData(data, name != null ? name : "file");
                } catch (IOException e) { System.err.println("[iLink] ⚠ 下载文件失败: " + e.getMessage()); }
            }
        }
        return null;
    }

    private record FileData(byte[] bytes, String name) {}
}
