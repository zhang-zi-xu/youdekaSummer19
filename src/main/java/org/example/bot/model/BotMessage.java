package org.example.bot.model;

/**
 * 微信消息的统一数据载体 — 支持文字、图片、语音、文件四种类型。
 */
public class BotMessage {

    private final String userId;
    private final String text;
    private final byte[] imageBytes;
    private final byte[] voiceBytes;
    private final String voiceText;
    private final byte[] fileBytes;
    private final String fileName;

    private BotMessage(String userId, String text, byte[] imageBytes,
                       byte[] voiceBytes, String voiceText,
                       byte[] fileBytes, String fileName) {
        this.userId = userId;
        this.text = text;
        this.imageBytes = imageBytes;
        this.voiceBytes = voiceBytes;
        this.voiceText = voiceText;
        this.fileBytes = fileBytes;
        this.fileName = fileName;
    }

    public static BotMessage text(String userId, String text) {
        return new BotMessage(userId, text, null, null, null, null, null);
    }

    public static BotMessage image(String userId, byte[] imageBytes, String text) {
        return new BotMessage(userId, text != null ? text : "", imageBytes, null, null, null, null);
    }

    public static BotMessage voice(String userId, byte[] voiceBytes, String voiceText) {
        return new BotMessage(userId, voiceText != null ? voiceText : "", null,
                voiceBytes, voiceText, null, null);
    }

    public static BotMessage file(String userId, byte[] fileBytes, String fileName) {
        return new BotMessage(userId, "", null, null, null, fileBytes, fileName);
    }

    public String userId() { return userId; }
    public String text() { return text; }
    public byte[] imageBytes() { return imageBytes; }
    public byte[] voiceBytes() { return voiceBytes; }
    public String voiceText() { return voiceText; }
    public byte[] fileBytes() { return fileBytes; }
    public String fileName() { return fileName; }

    public boolean isImage() { return imageBytes != null && imageBytes.length > 0; }
    public boolean isVoice() {
        return (voiceBytes != null && voiceBytes.length > 0)
            || (voiceText != null && !voiceText.isBlank());
    }
    public boolean isFile() { return fileBytes != null && fileBytes.length > 0; }
}
