package org.example.bot.impl;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.utils.Constants;
import org.example.bot.service.SpeechService;
import org.example.bot.util.ConfigUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 阿里云百炼 Qwen TTS 语音合成 + 音色管理。
 */
public class QwenTtsSpeechServiceImpl implements SpeechService {

    private static final String MODEL = "qwen3-tts-flash";
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final Map<String, String> voiceLibrary = new LinkedHashMap<>();
    private String currentVoice = "Cherry";

    public QwenTtsSpeechServiceImpl() {
        String apiKey = ConfigUtil.get("dashscope.api.key", "DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未找到 DashScope API Key。请设置 DASHSCOPE_API_KEY。");
        }
        Constants.apiKey = apiKey.trim();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DOWNLOAD_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();

        // 注册预设音色
        voiceLibrary.put("Cherry", "温柔女声");
        voiceLibrary.put("Serena", "成熟女声");
        voiceLibrary.put("Sunny", "活泼女声");
        voiceLibrary.put("Stella", "甜美女声");
        voiceLibrary.put("Ethan", "磁性男声");
        voiceLibrary.put("Roy", "沉稳男声");
        voiceLibrary.put("Aiden", "阳光男声");
        voiceLibrary.put("Momo", "可爱女声");
        voiceLibrary.put("Kai", "少年音");
        voiceLibrary.put("Bunny", "俏皮女声");
        voiceLibrary.put("Mia", "清新女声");
        voiceLibrary.put("Mochi", "奶萌音");
        voiceLibrary.put("Pip", "元气少女");
        voiceLibrary.put("Kiki", "萝莉音");

        System.out.println("[TTS] Qwen TTS 已就绪（默认音色: " + currentVoice + "）");
    }

    @Override
    public byte[] textToSpeech(String text) {
        AudioParameters.Voice voice = parseVoice(currentVoice);
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .model(MODEL).text(text).voice(voice).build();
        MultiModalConversation conv = new MultiModalConversation();
        MultiModalConversationResult result;
        try { result = conv.call(param); }
        catch (Exception e) { throw new RuntimeException("TTS 失败: " + e.getMessage(), e); }

        if (result.getStatusCode() != null && result.getStatusCode() != 200)
            throw new RuntimeException("TTS API 错误: " + result.getCode());

        String url = result.getOutput().getAudio().getUrl();
        if (url == null || url.isBlank()) {
            String data = result.getOutput().getAudio().getData();
            if (data != null && !data.isBlank()) return java.util.Base64.getDecoder().decode(data);
            throw new RuntimeException("TTS 未返回音频");
        }
        try { return downloadAudio(url); }
        catch (Exception e) { throw new RuntimeException("下载音频失败: " + e.getMessage(), e); }
    }

    @Override
    public void setVoice(String voiceId) {
        if (voiceLibrary.containsKey(voiceId)) {
            currentVoice = voiceId;
            System.out.println("[TTS] 音色: " + voiceId);
        } else {
            throw new RuntimeException("未知音色: " + voiceId + "。用「查看音色库」查看可用音色。");
        }
    }

    @Override public String getCurrentVoice() { return currentVoice; }

    @Override
    public String listVoices() {
        StringBuilder sb = new StringBuilder("🎤 音色库：\n");
        for (var e : voiceLibrary.entrySet()) {
            sb.append(e.getKey().equals(currentVoice) ? "  ● " : "  ○ ");
            sb.append(e.getKey()).append(" — ").append(e.getValue());
            if (e.getKey().equals(currentVoice)) sb.append(" ← 当前");
            sb.append("\n");
        }
        sb.append("\n切换音色：切换音色 <名称>");
        return sb.toString();
    }

    private AudioParameters.Voice parseVoice(String v) {
        try { return AudioParameters.Voice.valueOf(v.toUpperCase()); }
        catch (IllegalArgumentException e) { return AudioParameters.Voice.CHERRY; }
    }

    private byte[] downloadAudio(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(DOWNLOAD_TIMEOUT).GET().build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        byte[] data = resp.body();
        System.out.println("[TTS] 音频: " + (data.length / 1024) + " KB");
        return data;
    }
}
