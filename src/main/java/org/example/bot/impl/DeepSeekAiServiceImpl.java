package org.example.bot.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import org.example.bot.model.*;
import org.example.bot.service.AiService;
import org.example.bot.util.ConfigUtil;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * DeepSeek AI 对话服务 — 支持多会话隔离。
 */
public class DeepSeekAiServiceImpl implements AiService {

    private static final String BASE_URL = "https://api.deepseek.com/v1";
    private static final String MODEL = "deepseek-v4-pro";
    private static final int MAX_HISTORY = SessionManager.MAX_HISTORY;

    private static final String INTENT_SYSTEM_PROMPT =
        "你是一个意图识别助手。只输出JSON，不要任何解释或markdown代码块。";

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

    // ---- 意图提取 ----

    @Override
    public DrawIntent extractDrawIntent(String userMessage) {
        String prompt = "分析这条消息是否想让AI生成/画/创建一张图片。" +
            "如果是，提取主题描述。不是则isDraw=false。" +
            "严格输出JSON：{\"isDraw\":true|false,\"prompt\":\"描述\"}\n\n" +
            "消息：" + userMessage;

        try {
            JsonObject obj = gson.fromJson(quickAsk(prompt), JsonObject.class);
            boolean isDraw = obj.has("isDraw") && obj.get("isDraw").getAsBoolean();
            String text = obj.has("prompt") && !obj.get("prompt").isJsonNull()
                ? obj.get("prompt").getAsString() : "";
            return new DrawIntent(isDraw, text.isBlank() ? "" : text);
        } catch (Exception e) {
            return DrawIntent.notDraw();
        }
    }

    @Override
    public WeatherIntent extractWeatherIntent(String userMessage) {
        String prompt = "分析是否在询问天气。如果是，提取城市名。" +
            "输出JSON：{\"isWeather\":true|false,\"city\":\"城市\"}\n\n" +
            "消息：" + userMessage;

        try {
            JsonObject obj = gson.fromJson(quickAsk(prompt), JsonObject.class);
            boolean isWeather = obj.has("isWeather") && obj.get("isWeather").getAsBoolean();
            String city = obj.has("city") && !obj.get("city").isJsonNull()
                ? obj.get("city").getAsString() : "";
            return new WeatherIntent(isWeather, city);
        } catch (Exception e) {
            return WeatherIntent.notWeather();
        }
    }

    @Override
    public ImageFollowUpIntent extractImageFollowUpIntent(String userMessage) {
        String prompt = "用户之前发了一张图片，现在说了这句话。" +
            "判断是否在追问图片内容。输出JSON：{\"isFollowUp\":true|false}\n\n" +
            "消息：" + userMessage;

        try {
            JsonObject obj = gson.fromJson(quickAsk(prompt), JsonObject.class);
            boolean isFollowUp = obj.has("isFollowUp") && obj.get("isFollowUp").getAsBoolean();
            return new ImageFollowUpIntent(isFollowUp);
        } catch (Exception e) {
            return ImageFollowUpIntent.notFollowUp();
        }
    }

    @Override
    public VoiceReplyIntent extractVoiceReplyIntent(String userMessage) {
        String prompt = "判断用户是否明确要求用语音/说话的方式回复。" +
            "比如「讲话告诉我」「发语音」「用语音回复」「说给我听」。" +
            "正常聊天不算。输出JSON：{\"isVoiceReply\":true|false}\n\n" +
            "消息：" + userMessage;

        try {
            JsonObject obj = gson.fromJson(quickAsk(prompt), JsonObject.class);
            boolean isVoiceReply = obj.has("isVoiceReply") && obj.get("isVoiceReply").getAsBoolean();
            return new VoiceReplyIntent(isVoiceReply);
        } catch (Exception e) {
            return VoiceReplyIntent.notVoiceReply();
        }
    }

    @Override
    public SessionCommandIntent extractSessionCommandIntent(String userMessage) {
        String prompt = "判断是否为对话管理命令。" +
            "action取值：create(新建对话)/switch(切换到)/delete(删掉)/list(查看所有)/none。" +
            "name为对话名称，没有则为空字符串。" +
            "严格输出JSON：{\"action\":\"create|switch|delete|list|none\",\"name\":\"名称\"}\n\n" +
            "消息：" + userMessage;

        try {
            JsonObject obj = gson.fromJson(quickAsk(prompt), JsonObject.class);
            String action = obj.has("action") ? obj.get("action").getAsString() : "none";
            String name = obj.has("name") && !obj.get("name").isJsonNull()
                ? obj.get("name").getAsString() : "";
            return new SessionCommandIntent(action, name);
        } catch (Exception e) {
            return SessionCommandIntent.none();
        }
    }

    // ---- 统一 Function Calling（所有工具）----

    @Override
    public String chatWithTools(String userId, String userMessage,
                                List<FunctionDefinition> tools,
                                Map<String, java.util.function.Function<JsonObject, String>> executors) {
        Session session = sessionManager.getOrCreate(userId);
        session.add("user", userMessage);
        session.trim(MAX_HISTORY);

        try {
            // ===== 步骤 1: 带全部工具发起请求 =====
            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .addSystemMessage(sessionManager.fullSystemPrompt(session));

            for (int i = 0; i < session.roles.size(); i++) {
                if ("user".equals(session.roles.get(i))) {
                    builder.addUserMessage(session.contents.get(i));
                } else {
                    builder.addAssistantMessage(session.contents.get(i));
                }
            }

            // 注册所有工具
            for (FunctionDefinition tool : tools) {
                builder.addFunctionTool(tool);
            }

            builder.model(MODEL);
            ChatCompletion completion = client.chat().completions().create(builder.build());
            ChatCompletionMessage message = completion.choices().get(0).message();

            // ===== 步骤 2: 检查是否有 tool_calls =====
            List<ChatCompletionMessageToolCall> toolCalls = message.toolCalls().orElse(List.of());
            if (toolCalls.isEmpty()) {
                // AI 判断不需要任何工具 → 返回 null，降级到自由对话
                return null;
            }

            // ===== 步骤 3: 执行工具（只处理第一个 tool_call）=====
            ChatCompletionMessageToolCall toolCall = toolCalls.get(0);
            ChatCompletionMessageFunctionToolCall funcCall = toolCall.asFunction();
            String funcName = funcCall.function().name();
            String arguments = funcCall.function().arguments();
            System.out.println("[FC] AI 调用工具: " + funcName + "(" + arguments + ")");

            JsonObject args = gson.fromJson(arguments, JsonObject.class);
            java.util.function.Function<JsonObject, String> executor = executors.get(funcName);
            String toolResult;
            if (executor != null) {
                toolResult = executor.apply(args);
            } else {
                toolResult = "工具 " + funcName + " 未注册执行器";
            }

            // ===== 步骤 4: 把工具结果发回 AI 做第二轮对话 =====
            ChatCompletionCreateParams.Builder followUp = ChatCompletionCreateParams.builder()
                    .addSystemMessage(sessionManager.fullSystemPrompt(session));

            for (int i = 0; i < session.roles.size() - 1; i++) {  // 不含刚加入的 user message
                if ("user".equals(session.roles.get(i))) {
                    followUp.addUserMessage(session.contents.get(i));
                } else {
                    followUp.addAssistantMessage(session.contents.get(i));
                }
            }
            followUp.addUserMessage(userMessage);
            followUp.addMessage(ChatCompletionAssistantMessageParam.builder()
                    .toolCalls(message.toolCalls().orElse(List.of()))
                    .build());
            followUp.addMessage(ChatCompletionToolMessageParam.builder()
                    .toolCallId(funcCall.id())
                    .content(toolResult)
                    .build());

            followUp.model(MODEL);
            ChatCompletion finalCompletion = client.chat().completions().create(followUp.build());
            String finalReply = finalCompletion.choices().get(0).message().content().orElse("");

            session.add("assistant", finalReply);
            return finalReply;

        } catch (Exception e) {
            System.err.println("[AI] ❌ Function Calling 失败: " + e.getMessage());
            return null; // 降级到自由对话
        }
    }

    // ---- Function Calling 天气（已废弃，保留兼容）----

    @Override
    public String chatWithWeatherTool(String userId, String userMessage,
                                       Function<String, String> weatherExecutor) {
        Session session = sessionManager.getOrCreate(userId);
        session.add("user", userMessage);
        session.trim(MAX_HISTORY);

        // ===== 步骤 1: 定义天气工具 =====
        // JSON Schema: {type: "object", properties: {city: {type: "string", description: "城市名"}}}
        Map<String, Object> cityProp = Map.of("type", "string", "description", "城市名称，例如：北京、上海、东京");
        Map<String, Object> props = Map.of("city", cityProp);
        Map<String, Object> paramsSchema = Map.of("type", "object", "properties", props);

        FunctionDefinition weatherFunc = FunctionDefinition.builder()
                .name("get_weather")
                .description("查询指定城市的实时天气信息，包括温度、体感温度、湿度、天气状况、风速风向")
                .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(props))
                        .build())
                .build();

        try {
            // ===== 步骤 2: 带 tool 发起请求 =====
            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .addSystemMessage(sessionManager.fullSystemPrompt(session));

            for (int i = 0; i < session.roles.size(); i++) {
                if ("user".equals(session.roles.get(i))) {
                    builder.addUserMessage(session.contents.get(i));
                } else {
                    builder.addAssistantMessage(session.contents.get(i));
                }
            }

            builder.addFunctionTool(weatherFunc).model(MODEL);
            ChatCompletion completion = client.chat().completions().create(builder.build());
            ChatCompletionMessage message = completion.choices().get(0).message();

            // ===== 步骤 3: 检查是否有 tool_calls =====
            List<ChatCompletionMessageToolCall> toolCalls = message.toolCalls().orElse(List.of());
            if (toolCalls == null || toolCalls.isEmpty()) {
                // AI 判断不需要天气工具 → 返回 null，消息继续往后走
                return null;
            }

            // ===== 步骤 4: 执行天气查询 =====
            ChatCompletionMessageToolCall toolCall = toolCalls.get(0);
            ChatCompletionMessageFunctionToolCall funcCall = toolCall.asFunction();
            String funcName = funcCall.function().name();
            String arguments = funcCall.function().arguments();
            System.out.println("[FC] AI 调用工具: " + funcName + "(" + arguments + ")");

            // 解析参数
            JsonObject args = gson.fromJson(arguments, JsonObject.class);
            String city = args.has("city") ? args.get("city").getAsString() : userMessage;
            String weatherResult = weatherExecutor.apply(city);

            // ===== 步骤 5: 把结果发回 AI 做第二轮对话 =====
            ChatCompletionCreateParams.Builder followUp = ChatCompletionCreateParams.builder()
                    .addSystemMessage(sessionManager.fullSystemPrompt(session));

            for (int i = 0; i < session.roles.size() - 1; i++) {  // 不包含刚加入的 user message
                if ("user".equals(session.roles.get(i))) {
                    followUp.addUserMessage(session.contents.get(i));
                } else {
                    followUp.addAssistantMessage(session.contents.get(i));
                }
            }
            // 加入最新的 user message
            followUp.addUserMessage(userMessage);
            // 加入 assistant 的 tool_call
            ChatCompletionAssistantMessageParam assistantMsg =
                ChatCompletionAssistantMessageParam.builder()
                    .toolCalls(message.toolCalls().orElse(List.of()))
                    .build();
            followUp.addMessage(assistantMsg);
            // 加入 tool 执行结果
            followUp.addMessage(ChatCompletionToolMessageParam.builder()
                    .toolCallId(funcCall.id())
                    .content(weatherResult)
                    .build());

            followUp.model(MODEL);
            ChatCompletion finalCompletion = client.chat().completions().create(followUp.build());
            String finalReply = finalCompletion.choices().get(0).message().content().orElse("");

            session.add("assistant", finalReply);
            return finalReply;

        } catch (Exception e) {
            System.err.println("[AI] ❌ Function Calling 失败: " + e.getMessage());
            return "Function Calling 演示失败: " + e.getMessage();
        }
    }

    // ---- 内部方法 ----

    private String quickAsk(String prompt) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addSystemMessage(INTENT_SYSTEM_PROMPT)
                .addUserMessage(prompt)
                .model(MODEL)
                .build();

        ChatCompletion completion = client.chat().completions().create(params);
        String content = completion.choices().get(0).message().content().orElse("{}");
        content = content.strip();
        if (content.startsWith("```")) {
            content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").strip();
        }
        return content;
    }
}
