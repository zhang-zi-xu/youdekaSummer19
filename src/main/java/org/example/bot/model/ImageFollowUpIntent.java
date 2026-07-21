package org.example.bot.model;

/**
 * 图片追问意图提取结果 — 由 AI 分析用户消息后返回。
 *
 * @param isFollowUp 用户是否在追问上一张图片的内容
 */
public record ImageFollowUpIntent(boolean isFollowUp) {

    public static ImageFollowUpIntent notFollowUp() {
        return new ImageFollowUpIntent(false);
    }

    public static ImageFollowUpIntent followUp() {
        return new ImageFollowUpIntent(true);
    }
}
