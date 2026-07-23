package org.example.bot.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.FunctionDefinition;
import com.openai.models.chat.completions.*;
import org.example.bot.service.AiService;
import org.example.bot.util.ConfigUtil;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek AI 对话服务 — 支持多会话隔离 + Function Calling。
 */
public class DeepSeekAiServiceImpl implements AiService {

    private static final String BASE_URL = "https://api.deepseek.com/v1";
    private static final String MODEL = "deepseek-v4-pro";
    private static final int MAX_HISTORY = SessionManager.MAX_HISTORY;

    private final OpenAIClient client;
    private final Gson gson = new Gson();
    private final SessionManager sessionManager;

    public DeepSeekAiServiceImpl(String defaultPersona, String techInstructions) {

        String apiKey = ConfigUtil.get("deepseek.api.key", "DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "未找到 DeepSeek API Key。请在 config.properties 中设置 deepseek.api.key，\n"
                + "或设置环境变量 DEEPSEEK_API_KEY。");
        }

        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey.trim())
                .baseUrl(BASE_URL)
                .build();
        this.sessionManager = new SessionManager(defaultPersona, techInstructions);

        System.out.println("[AI] DeepSeek 服务已就绪（模型: " + MODEL + "）");
    }

    @Override
    public void setPersona(String userId, String persona) {
        sessionManager.setPersona(userId, persona);
    }

    // ---- 多会话聊天 ----

    @Override
    public String chat(String userId, String userMessage) {
        Session session = sessionManager.getOrCreate(userId);

        session.add("user", userMessage);
        session.trim(MAX_HISTORY);

        try {
            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .addSystemMessage(sessionManager.fullSystemPrompt(session));

            for (int i = 0; i < session.roles.size(); i++) {
                if ("user".equals(session.roles.get(i))) {
                    builder.addUserMessage(session.contents.get(i));
                } else {
                    builder.addAssistantMessage(session.contents.get(i));
                }
            }

            ChatCompletion completion = client.chat().completions().create(
                    builder.model(MODEL).build());

            String reply = completion.choices().get(0).message().content().orElse("");
            session.add("assistant", reply);
            return reply;

        } catch (Exception e) {
            System.err.println("[AI] ❌ DeepSeek 调用失败: " + e.getMessage());
            return "抱歉，我暂时无法回复，请稍后再试。";
        }
    }

    // ---- 会话管理 ----

    public SessionManager getSessionManager() { return sessionManager; }

    @Override
    public String getHelpMessage() { return SessionManager.HELP_MESSAGE; }

    @Override
    public void record(String userId, String userInput, String assistantOutput) {
        Session session = sessionManager.getOrCreate(userId);
        session.add("user", userInput);
        session.add("assistant", assistantOutput);
        session.trim(MAX_HISTORY);
    }

    // ---- 统一 Function Calling ----

    private static final int MAX_FC_ROUNDS = 5; // 最多工具调用轮次，防止无限循环

    @Override
    public String chatWithTools(String userId, String userMessage,
                                List<FunctionDefinition> tools,
                                Map<String, java.util.function.Function<JsonObject, String>> executors) {
        Session session = sessionManager.getOrCreate(userId);
        session.add("user", userMessage);
        session.trim(MAX_HISTORY);

        try {
            // 步骤 1: 构建请求 — 系统提示 + 对话历史 + 全部工具
            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .addSystemMessage(sessionManager.fullSystemPrompt(session));

            for (int i = 0; i < session.roles.size(); i++) {
                if ("user".equals(session.roles.get(i))) {
                    builder.addUserMessage(session.contents.get(i));
                } else {
                    builder.addAssistantMessage(session.contents.get(i));
                }
            }

            for (FunctionDefinition tool : tools) {
                builder.addFunctionTool(tool);
            }

            builder.model(MODEL);
            ChatCompletionMessage message = client.chat().completions()
                    .create(builder.build()).choices().get(0).message();

            // 步骤 2: 循环 — AI 可能连续调用多轮工具
            for (int round = 0; round < MAX_FC_ROUNDS; round++) {
                List<ChatCompletionMessageToolCall> toolCalls =
                    message.toolCalls().orElse(List.of());

                // 没有工具调用了 → 返回最终文本回复
                if (toolCalls.isEmpty()) {
                    String reply = message.content().orElse("");
                    if (!reply.isBlank()) {
                        session.add("assistant", reply);
                        return reply;
                    }
                    return null; // 第一轮就没有 tool_call 也没内容 → 降级
                }

                // 记录 assistant 的 tool_calls 到对话
                builder.addMessage(ChatCompletionAssistantMessageParam.builder()
                        .toolCalls(message.toolCalls().orElse(List.of()))
                        .build());

                // 执行本轮所有工具
                for (ChatCompletionMessageToolCall tc : toolCalls) {
                    ChatCompletionMessageFunctionToolCall funcCall = tc.asFunction();
                    String funcName = funcCall.function().name();
                    String arguments = funcCall.function().arguments();
                    System.out.println("[FC] AI 调用工具: " + funcName + "(" + arguments + ")");

                    JsonObject args = gson.fromJson(
                        arguments != null ? arguments : "{}", JsonObject.class);
                    java.util.function.Function<JsonObject, String> executor =
                        executors.get(funcName);
                    String result = executor != null
                        ? executor.apply(args)
                        : "工具 " + funcName + " 未注册执行器";

                    builder.addMessage(ChatCompletionToolMessageParam.builder()
                            .toolCallId(funcCall.id())
                            .content(result)
                            .build());
                }

                // 继续对话 — AI 可能再调工具或返回最终文本
                builder.model(MODEL);
                message = client.chat().completions().create(builder.build())
                        .choices().get(0).message();
            }

            // 超过最大轮次 — 返回最后一条消息（不含 tool_calls 的）
            String reply = message.content().orElse("抱歉，处理超时，请简化你的请求。");
            session.add("assistant", reply);
            return reply;

        } catch (Exception e) {
            System.err.println("[AI] ❌ Function Calling 失败: " + e.getMessage());
            return null;
        }
    }
}
