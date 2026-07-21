package org.example.bot.model;

/**
 * 语音回复意图提取结果 — 由 AI 分析用户消息后返回。
 *
 * @param isVoiceReply 用户是否要求用语音回复
 */
public record VoiceReplyIntent(boolean isVoiceReply) {

    public static VoiceReplyIntent notVoiceReply() {
        return new VoiceReplyIntent(false);
    }

    public static VoiceReplyIntent voiceReply() {
        return new VoiceReplyIntent(true);
    }
}
