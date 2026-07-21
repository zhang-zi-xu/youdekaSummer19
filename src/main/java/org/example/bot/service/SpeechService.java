package org.example.bot.service;

/**
 * 语音服务接口 — 文字转语音 + 音色管理。
 */
public interface SpeechService {

    /** 将文字合成为语音 */
    byte[] textToSpeech(String text);

    /** 切换当前音色 */
    void setVoice(String voiceId);

    /** 获取当前音色 */
    String getCurrentVoice();

    /** 列出所有可用音色 */
    String listVoices();
}
