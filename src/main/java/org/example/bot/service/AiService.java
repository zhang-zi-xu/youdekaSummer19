package org.example.bot.service;

import com.openai.models.FunctionDefinition;
import com.google.gson.JsonObject;
import org.example.bot.model.DrawIntent;
import org.example.bot.model.ImageFollowUpIntent;
import org.example.bot.model.SessionCommandIntent;
import org.example.bot.model.VoiceReplyIntent;
import org.example.bot.model.WeatherIntent;

import java.util.List;
import java.util.Map;

/**
 * AI 服务接口 — 所有 AI 对话实现（DeepSeek、OpenAI、Claude 等）都要实现此接口。
 *
 * <p>每个方法都需要 {@code userId} 参数以支持多会话隔离。
 */
public interface AiService {

    /**
     * 接收用户消息，返回 AI 回复。使用该 userId 对应的当前会话历史。
     */
    String chat(String userId, String userMessage);

    /**
     * [统一 Function Calling] 一次 AI 调用，注册所有工具，AI 自主决定调用哪个。
     *
     * @param tools     所有可用的工具定义列表
     * @param executors 工具名 → 执行函数（接收 JSON 参数，返回执行结果字符串）
     * @return AI 的最终文本回复；如果 AI 没有调用任何工具则返回 {@code null}，
     *         调用方应降级到 {@link #chat(String, String)} 自由对话
     */
    String chatWithTools(String userId, String userMessage,
                         List<FunctionDefinition> tools,
                         Map<String, java.util.function.Function<JsonObject, String>> executors);

    /** 分析是否想生成图片 */
    DrawIntent extractDrawIntent(String userMessage);

    /** 分析是否在查询天气 */
    WeatherIntent extractWeatherIntent(String userMessage);

    /** 分析是否在追问上一张图片 */
    ImageFollowUpIntent extractImageFollowUpIntent(String userMessage);

    /** 分析是否要求用语音回复 */
    VoiceReplyIntent extractVoiceReplyIntent(String userMessage);

    /**
     * 分析是否为会话管理命令（新建/切换/删除/查看对话）。
     * 失败时返回 {@code SessionCommandIntent.none()}。
     */
    SessionCommandIntent extractSessionCommandIntent(String userMessage);

    /**
     * 向当前会话历史中插入一条交互记录（不调用 AI）。
     */
    void record(String userId, String userInput, String assistantOutput);

    /** 获取会话帮助指南 */
    String getHelpMessage();

    /** 修改当前会话的人设 */
    void setPersona(String userId, String persona);

    /**
     * @deprecated 请使用 {@link #chatWithTools(String, String, List, Map)}，
     *             将天气注册为其中一个工具即可。
     */
    @Deprecated
    String chatWithWeatherTool(String userId, String userMessage,
                               java.util.function.Function<String, String> weatherExecutor);
}
