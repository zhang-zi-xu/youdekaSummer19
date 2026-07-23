package org.example.bot;

import org.example.bot.ilink.ILinkBot;
import org.example.bot.model.BotMessage;
import org.example.bot.service.*;
import org.example.bot.impl.DateTimeServiceImpl;
import org.example.bot.impl.DeepSeekAiServiceImpl;
import org.example.bot.impl.DoubaoVisionServiceImpl;
import org.example.bot.impl.QwenTtsSpeechServiceImpl;
import org.example.bot.impl.SeedreamImageServiceImpl;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Function;

/**
 * 微信 AI 聊天机器人 — 主程序入口。
 *
 * <h3>消息路由策略</h3>
 * <ol>
 *   <li>图片消息 → 视觉识别（Vision API）</li>
 *   <li>文字包含"画/生成/图"等关键词 → AI 判断是否为生图意图 → Seedream 生图</li>
 *   <li>文字包含"天气/气温/下雨"等关键词 → AI 判断是否为天气查询 → 和风天气</li>
 *   <li>以上都不匹配 → DeepSeek 自由对话</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>{@code mvn compile exec:java -Dexec.mainClass="org.example.bot.BotApp"}</pre>
 */
public class BotApp {

    static DateTimeService dateTime = new DateTimeServiceImpl();
    /** 默认人设 */
    private static final String DEFAULT_PERSONA = "你是一个友好的微信AI助手。";
    /** 技术指令（不随人设变化，始终追加） */
    private static final String TECH_INSTRUCTIONS =
        "你有语音回复能力，用户要求语音时你的文字会自动转语音，所以不要说你不能发语音。" +
        "回复简洁自然，适合朗读。";

    /** 生图专用线程池 — 避免阻塞主消息循环 */
    private static final ExecutorService IMAGE_EXECUTOR =
        Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "image-gen");
            t.setDaemon(true);
            return t;
        });

    /** 上一张图片缓存 — 支持用户追问 */
    private static final Map<String, CachedImage> LAST_IMAGE = new HashMap<>();
    private static final long IMAGE_CACHE_TTL_MS = 5 * 60 * 1000;

    /** 上一份文档缓存 — 支持追问 */
    private static final Map<String, CachedDoc> LAST_DOC = new HashMap<>(); // 5 分钟

    private static class CachedImage {
        final byte[] bytes;
        final long timestamp;
        CachedImage(byte[] bytes) {
            this.bytes = bytes;
            this.timestamp = System.currentTimeMillis();
        }
        boolean expired() {
            return System.currentTimeMillis() - timestamp > IMAGE_CACHE_TTL_MS;
        }
    }

    private static class CachedDoc {
        final String content;
        final String fileName;
        final long timestamp;
        CachedDoc(String content, String fileName) {
            this.content = content; this.fileName = fileName;
            this.timestamp = System.currentTimeMillis();
        }
        boolean expired() { return System.currentTimeMillis() - timestamp > IMAGE_CACHE_TTL_MS; }
    }

    /** 图片追问关键词 — 仅匹配明显指向图片的说法（已废弃，FC 工具自动判断） */
    @Deprecated
    private static final String[] IMAGE_FOLLOWUP_KEYWORDS = {
        "照片", "图片", "这张", "那张", "图中", "图里", "这图", "那个图", "这里面", "图上"
    };
    /** 文档追问关键词（已废弃，FC 工具自动判断） */
    @Deprecated
    private static final String[] DOC_FOLLOWUP_KEYWORDS = {
        "文档", "文件", "这份", "那份", "刚才的文档", "刚才的文件", "总结的"
    };

    // 语音回复关键词 — 无需 AI 确认，关键词命中即生效
    private static final String[] VOICE_REPLY_KEYWORDS = {
        "语音", "讲话", "说话", "发语音", "用语音", "说给我", "讲给我", "念给我", "读给我"
    };

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  微信 AI 聊天机器人 启动中...");
        System.out.println("========================================");

        // 第 1 步：创建并登录微信机器人
        ILinkBot bot = ILinkBot.create();
        bot.login();

        // 第 2 步：创建服务
        AiService ai = new DeepSeekAiServiceImpl(DEFAULT_PERSONA, TECH_INSTRUCTIONS);
        WeatherBotService weather = WeatherBotService.create();

        ImageGenService imageGen = null;
        try { imageGen = new SeedreamImageServiceImpl(); }
        catch (IllegalStateException e) { System.out.println("[Bot] ⚠ 生图服务未启用: " + e.getMessage()); }

        VisionService vision = null;
        try { vision = new DoubaoVisionServiceImpl(); }
        catch (IllegalStateException e) { System.out.println("[Bot] ⚠ 识图服务未启用: " + e.getMessage()); }

        SpeechService tts = null;
        try { tts = new QwenTtsSpeechServiceImpl(); }
        catch (IllegalStateException e) { System.out.println("[Bot] ⚠ 语音合成服务未启用: " + e.getMessage()); }

        // ---- 捕获为 final 变量供 lambda 使用 ----
        final ImageGenService fImageGen = imageGen;
        final VisionService fVision = vision;
        final SpeechService fTts = tts;
        final AiService fAi = ai;
        final WeatherBotService fWeather = weather;

        // 第 3 步：注册消息处理器 — 每条消息到达时直接处理
        bot.setHandler(msg -> {
            String userId = msg.userId();

            if (msg.isVoice()) {
                System.out.println("[收到] " + userId + " : [语音] "
                    + (msg.voiceText() != null ? msg.voiceText() : ""));
                handleVoice(bot, fAi, fTts, fWeather, fVision, fImageGen, msg);
                return;
            }
            if (msg.isImage()) {
                System.out.println("[收到] " + userId + " : [图片] " + msg.text());
                handleImage(bot, fAi, fVision, msg);
                return;
            }
            if (msg.isFile()) {
                System.out.println("[收到] " + userId + " : [文件] " + msg.fileName());
                handleFile(bot, fAi, userId, msg);
                return;
            }
            // 文字消息
            String text = msg.text().strip();
            System.out.println("[收到] " + userId + " : " + text);
            processTextMessage(bot, fAi, fTts, fWeather, fVision, fImageGen,
                               userId, text, false);
        });

        System.out.println("\n[Bot] 🟢 开始监听消息...（按 Ctrl+C 退出）\n");
        bot.startPolling();

        // 主线程阻塞，消息由 handler 线程处理
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("\n[Bot] 收到退出信号...");
        } finally {
            bot.close();
            IMAGE_EXECUTOR.shutdown();
            try { IMAGE_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
            System.out.println("[Bot] 已安全退出。");
        }
    }

    // ============================================================
    //  统一文字消息路由
    //  优先级：本地命令 → Function Calling（AI 选工具）→ 自由对话
    // ============================================================

    /**
     * 统一处理文字消息（也用于语音消息的文字部分）。
     *
     * @param forceVoice {@code true} 表示这条消息来自语音输入，回复必须带语音
     */
    private static void processTextMessage(ILinkBot bot, AiService ai, SpeechService tts,
                                           WeatherBotService weather, VisionService vision,
                                           ImageGenService imageGen,
                                           String userId, String text, boolean forceVoice) {
        // ① 本地命令 — 精确/前缀匹配，零 API 消耗
        if (tryHandleLocalCommand(bot, ai, tts, userId, text)) return;

        // ② 语音意图 — 关键词命中即生效（不再额外调 AI 确认）
        boolean wantsVoice = forceVoice
            || (tts != null && containsKeyword(text, VOICE_REPLY_KEYWORDS));
        if (wantsVoice) System.out.println("[Bot] 🔊 语音回复");

        // ③ 构建工具列表 — 根据当前状态动态决定哪些工具可用
        java.util.List<FunctionDefinition> tools = new java.util.ArrayList<>();
        java.util.Map<String, java.util.function.Function<JsonObject, String>> executors
            = new java.util.LinkedHashMap<>();
        buildTools(tools, executors, bot, ai, weather, vision, imageGen, dateTime ,userId);

        // ④ 统一 Function Calling — 一次 API 调用，AI 自主决定用哪个工具
        if (!tools.isEmpty()) {
            String fcResult = ai.chatWithTools(userId, text, tools, executors);
            if (fcResult != null) {
                System.out.println("[回复] " + fcResult);
                bot.sendTextWithTyping(userId, fcResult, 500L);
                if (wantsVoice || isVoiceMode(ai, userId))
                    sendAsVoice(bot, tts, userId, fcResult);
                return;
            }
        }

        // ⑤ 降级：AI 自由对话
        System.out.println("[Bot] → AI 对话");
        String reply = ai.chat(userId, text);
        System.out.println("[回复] " + reply);
        bot.sendTextWithTyping(userId, reply, 500L);
        if (wantsVoice || isVoiceMode(ai, userId))
            sendAsVoice(bot, tts, userId, reply);
    }

    // ---- 本地命令（精确/前缀匹配，不消耗 AI 调用）----

    private static boolean tryHandleLocalCommand(ILinkBot bot, AiService ai, SpeechService tts,
                                                  String userId, String text) {
        // "帮助" / "help"
        if (text.equals("帮助") || text.equalsIgnoreCase("help")) {
            bot.sendText(userId, ai.getHelpMessage());
            System.out.println("[回复] 帮助面板");
            return true;
        }

        // "设定人设xxx" / "人设xxx"
        if (text.startsWith("设定人设") || text.startsWith("人设")) {
            String persona = text.replaceFirst("^(设定人设|人设)[：:]?\\s*", "");
            if (!persona.isBlank()) {
                ai.setPersona(userId, persona);
                bot.sendText(userId, "✅ 人设已更新：「" + persona + "」");
                System.out.println("[回复] 人设已更新: " + persona);
            } else {
                bot.sendText(userId, "请告诉我想设定的人设，例如：设定人设：你是一只可爱的猫娘");
            }
            return true;
        }

        // "开启语音模式" / "关闭语音模式"
        if ((text.equals("开启语音模式") || text.equals("关闭语音模式")) && tts != null) {
            var sm = ((DeepSeekAiServiceImpl) ai).getSessionManager();
            boolean on = sm.toggleVoiceMode(userId);
            bot.sendText(userId, on ? "✅ 语音模式已开启，所有回复将附带语音。"
                                    : "🔇 语音模式已关闭。");
            return true;
        }

        // "切换音色 xxx"
        if (text.startsWith("切换音色") && tts != null) {
            String voiceName = text.replaceFirst("^切换音色\\s*", "").strip();
            try {
                tts.setVoice(voiceName);
                bot.sendText(userId, "✅ 已切换到音色「" + voiceName + "」。");
            } catch (Exception e) {
                bot.sendText(userId, e.getMessage());
            }
            return true;
        }

        // "查看音色库" / "音色库"
        if ((text.equals("查看音色库") || text.equals("音色库")) && tts != null) {
            bot.sendText(userId, tts.listVoices());
            return true;
        }

        return false;
    }

    // ---- 工具构建（动态注册 — 根据服务可用性和缓存状态）----

    /**
     * 构建当前可用的 Function Calling 工具列表。
     * 条件工具（图片追问/文档追问）只在有缓存时才注册，避免 AI 幻觉调用。
     */
    private static void buildTools(
            List<FunctionDefinition> tools,
            Map<String, Function<JsonObject, String>> executors,
            ILinkBot bot, AiService ai,
            WeatherBotService weather, VisionService vision,
            ImageGenService imageGen, DateTimeService dateTime, String userId) {

        // --- 天气查询（始终可用）---
        tools.add(functionDef("get_weather",
            "查询指定城市的实时天气信息，包括温度、体感温度、湿度、天气状况、风速风向。" +
            "当用户询问天气、气温、会不会下雨、冷不冷、热不热、穿什么衣服等问题时调用此工具。",
            Map.of("city", Map.of("type", "string", "description", "城市名称，例如：北京、上海、东京"))));
        executors.put("get_weather", args -> {
            String city = args.has("city") ? args.get("city").getAsString() : "";
            return weather != null ? weather.query(city) : "天气服务未配置";
        });

        // --- 日期时间查询（独立工具，始终可用）---
        tools.add(functionDef("get_datetime",
            "查询指定时区或城市的当前日期和时间。当用户问「现在几点」「xx时间」「今天几号」「现在是什么时间」等问题时调用。",
            Map.of("timezone", Map.of("type", "string",
                "description", "时区或城市名，例如：Asia/Shanghai、纽约、东京、伦敦"))));
        executors.put("get_datetime", args -> {
            String tz = args.has("timezone") ? args.get("timezone").getAsString() : "Asia/Shanghai";
            return dateTime.query(tz);          // ← 原来是 DateTimeService.query(tz)
        });

        // --- 图片生成（如果服务可用）---
        if (imageGen != null) {
            tools.add(functionDef("generate_image",
                "根据文字描述生成一张图片。当用户说「画」「生成」「来一张」「做一张」「帮我画」等时调用。",
                Map.of("prompt", Map.of("type", "string", "description", "图片的详细描述，例如：一只在屋顶看星星的橘猫"))));
            executors.put("generate_image", args -> {
                String prompt = args.has("prompt") ? args.get("prompt").getAsString() : "";
                if (prompt.isBlank()) return "用户没有提供图片描述";
                // 异步生图 — 不阻塞当前回复
                final String p = prompt;
                final String uid = userId;
                IMAGE_EXECUTOR.submit(() -> {
                    try {
                        byte[] img = imageGen.generate(p);
                        bot.sendImage(uid, img, "generated.png", "「" + p + "」");
                        ai.record(uid, "请帮我生成一张图片：" + p, "图片已生成并发送");
                        System.out.println("[生图] 已发送: " + p);
                    } catch (Exception ex) {
                        System.err.println("[生图] ❌ 失败: " + ex.getMessage());
                        bot.sendText(uid, "抱歉，图片生成失败：" + ex.getMessage());
                    }
                });
                return "图片生成已启动，主题：「" + p + "」。预计 10~30 秒完成。";
            });
        }

        // --- 会话管理（始终可用）---
        var sm = ((DeepSeekAiServiceImpl) ai).getSessionManager();

        tools.add(functionDef("create_session",
            "创建一个新的对话会话。用户说「新建对话」「创建对话」「新对话」「开一个新对话」等时调用。",
            Map.of("name", Map.of("type", "string", "description", "新对话的名称，如果用户没有指定则填「默认」"))));
        executors.put("create_session", args -> {
            String name = args.has("name") ? args.get("name").getAsString() : "默认";
            sm.createSession(userId, name);
            return "已创建并切换到对话「" + name + "」。";
        });

        tools.add(functionDef("switch_session",
            "切换到指定的已有对话。用户说「切换到」「切换对话」「回到」等时调用。",
            Map.of("name", Map.of("type", "string", "description", "要切换到的对话名称"))));
        executors.put("switch_session", args -> {
            String name = args.has("name") ? args.get("name").getAsString() : "";
            sm.switchTo(userId, name);
            return "✅ 已切换到对话「" + name + "」。";
        });

        tools.add(functionDef("delete_session",
            "删除指定的对话会话。用户说「删掉」「删除对话」「移除」等时调用。",
            Map.of("name", Map.of("type", "string", "description", "要删除的对话名称"))));
        executors.put("delete_session", args -> {
            String name = args.has("name") ? args.get("name").getAsString() : "";
            return sm.deleteSession(userId, name);
        });

        tools.add(functionDef("list_sessions",
            "列出当前用户的所有对话会话。用户说「查看所有对话」「对话列表」「列表」「有哪些对话」等时调用。",
            Map.of()));
        executors.put("list_sessions", args -> sm.listSessions(userId));

        // --- 图片追问（条件：有缓存图片 + 识图服务可用）---
        CachedImage cachedImg = LAST_IMAGE.get(userId);
        if (cachedImg != null && !cachedImg.expired() && vision != null) {
            tools.add(functionDef("ask_about_image",
                "对用户之前发送的图片进行追问或分析。用户说「照片里」「图中」「这张图」「图片里有什么」等时调用。",
                Map.of("question", Map.of("type", "string", "description", "用户对图片的追问内容"))));
            final byte[] imgBytes = cachedImg.bytes;
            executors.put("ask_about_image", args -> {
                String question = args.has("question") ? args.get("question").getAsString() : "";
                try {
                    return vision.analyze(imgBytes, question.isBlank() ? "请详细描述这张图片" : question);
                } catch (Exception e) {
                    return "图片分析失败: " + e.getMessage();
                }
            });
        }

        // --- 文档追问（条件：有缓存文档）---
        CachedDoc cachedDoc = LAST_DOC.get(userId);
        if (cachedDoc != null && !cachedDoc.expired()) {
            tools.add(functionDef("ask_about_document",
                "对用户之前发送的文件/文档内容进行追问。用户说「文档里」「文件中」「刚才的文档」「这份文件」等时调用。",
                Map.of("question", Map.of("type", "string", "description", "用户对文档的追问内容"))));
            final String docContent = cachedDoc.content;
            final String docName = cachedDoc.fileName;
            executors.put("ask_about_document", args -> {
                String question = args.has("question") ? args.get("question").getAsString() : "";
                return "文件「" + docName + "」的内容如下：\n\n" + docContent
                    + "\n\n用户追问：" + question + "\n请根据文件内容回答。";
            });
        }
    }

    /** 快捷构建 FunctionDefinition */
    @SuppressWarnings("unchecked")
    private static FunctionDefinition functionDef(String name, String description,
                                                   Map<String, ?> properties) {
        return FunctionDefinition.builder()
                .name(name)
                .description(description)
                .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from((Object) properties))
                        .build())
                .build();
    }

    // ---- 语音回复辅助 ----

    private static void sendAsVoice(ILinkBot bot, SpeechService tts,
                                     String userId, String reply) {
        try {
            byte[] audio = tts.textToSpeech(cleanForTts(reply));
            // 只发音频文件，不再重复发文字
            bot.sendFile(userId, audio, "reply.wav", "");
            System.out.println("[语音] 已发送");
        } catch (Exception e) {
            System.err.println("[语音] ❌ TTS 失败: " + e.getMessage());
        }
    }

    // ---- 文件消息（提取文本 → AI 总结）----

    private static void handleFile(ILinkBot bot, AiService ai, String userId,
                                    BotMessage msg) {
        byte[] data = msg.fileBytes();
        String fileName = msg.fileName();
        if (data == null || data.length == 0) {
            bot.sendText(userId, "文件为空，无法处理。");
            return;
        }

        // 尝试多种编码读取文本文件
        String content = readTextFile(data);
        if (content == null) {
            // 尝试 PDF 提取
            content = readPdf(data);
        }
        if (content == null) {
            // 尝试 Word 提取
            content = readDocx(data);
        }
        if (content == null) {
            // 尝试 Excel 提取
            content = readXlsx(data);
        }

        if (content == null || content.isBlank()) {
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".")) : "";
            bot.sendText(userId, "「" + fileName + "」无法解析。\n目前支持：TXT、代码文件、CSV、日志等文本格式。");
            return;
        }

        int maxLen = 8000;
        String truncated = content.length() > maxLen ? content.substring(0, maxLen) + "\n...(后续内容已截断)" : content;

        System.out.println("[Bot] 📄 文件(" + content.length() + "字): " + truncated.substring(0, Math.min(80, truncated.length())));

        String reply = ai.chat(userId,
            "请总结以下文件「" + fileName + "」的内容，用简洁中文分点列出关键信息：\n\n" + truncated);
        System.out.println("[回复] " + reply);
        bot.sendTextWithTyping(userId, "📄 「" + fileName + "」总结：\n" + reply, 500L);

        // 缓存文档内容，支持追问
        LAST_DOC.put(userId, new CachedDoc(truncated, fileName));
    }

    /** 统计字符串中 Unicode 替换字符(�)的数量 */
    private static int countReplacementChars(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '�') count++;
        }
        return count;
    }

    /** 尝试多种编码读文本文件 */
    private static String readTextFile(byte[] data) {
        for (String enc : new String[]{"UTF-8", "GBK", "GB2312"}) {
            try {
                String s = new String(data, java.nio.charset.Charset.forName(enc));
                if (countReplacementChars(s) < s.length() * 0.05) return s;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** 提取 PDF 文本 */
    private static String readPdf(byte[] data) {
        try {
            var doc = org.apache.pdfbox.Loader.loadPDF(data);
            var stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setEndPage(20); // 最多读 20 页
            String text = stripper.getText(doc);
            doc.close();
            return text.strip();
        } catch (Exception e) {
            return null;
        }
    }

    /** 提取 Word 文本 */
    private static String readDocx(byte[] data) {
        try {
            var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(data));
            var sb = new StringBuilder();
            for (var p : doc.getParagraphs()) {
                sb.append(p.getText()).append("\n");
                if (sb.length() > 15000) break;
            }
            doc.close();
            return sb.toString().strip();
        } catch (Exception e) {
            return null;
        }
    }

    /** 提取 Excel 文本 */
    private static String readXlsx(byte[] data) {
        try {
            var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(data));
            var sb = new StringBuilder();
            var sheet = wb.getSheetAt(0);
            for (var row : sheet) {
                for (var cell : row) {
                    String v = cell.toString();
                    if (!v.isEmpty()) sb.append(v).append("\t");
                }
                sb.append("\n");
                if (sb.length() > 15000) break;
            }
            wb.close();
            return sb.toString().strip();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 图片识别 ----

    private static void handleImage(ILinkBot bot, AiService ai, VisionService vision,
                                      BotMessage msg) {
        if (vision == null) {
            bot.sendText(msg.userId(), "图片识别服务未启用，请联系管理员设置 ark.vision.api.key。");
            return;
        }
        String prompt = msg.text().isEmpty() ? null : msg.text();
        System.out.println("[Bot] 👁 检测到图片消息，开始识别...");
        String result = vision.analyze(msg.imageBytes(), prompt);
        System.out.println("[回复] " + result);
        // 缓存图片，支持后续追问
        LAST_IMAGE.put(msg.userId(), new CachedImage(msg.imageBytes()));

        // 记入对话历史，让 AI 知道刚才识图的内容
        ai.record(msg.userId(), "[发送了一张图片" + (prompt != null ? "，询问：" + prompt : "") + "]",
                  result);

        // 追加追问提示
        result += "\n\n💡 你可以继续追问，比如「照片里有什么动物」「这是什么地方」";
        bot.sendTextWithTyping(msg.userId(), result, 500L);
    }

    // ---- 语音消息（提取文字 → 统一路由 → 强制语音回复）----

    private static void handleVoice(ILinkBot bot, AiService ai, SpeechService tts,
                                     WeatherBotService weather, VisionService vision,
                                     ImageGenService imageGen, BotMessage msg) {
        String userId = msg.userId();
        String text = msg.voiceText();
        if (text == null || text.isBlank()) {
            bot.sendText(userId, "收到了你的语音，但无法识别内容。请尝试用文字发送～");
            return;
        }

        System.out.println("[Bot] 🎤 语音识别: " + text);
        // 统一走文字路由，forceVoice=true 确保回复一定带语音
        processTextMessage(bot, ai, tts, weather, vision, imageGen, userId, text, true);
    }

    // ---- 工具方法 ----

    /** 检查文本是否包含任意一个关键词 */
    private static boolean containsKeyword(String text, String[] keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private static boolean isVoiceMode(AiService ai, String userId) {
        return ((DeepSeekAiServiceImpl) ai).getSessionManager().isVoiceMode(userId);
    }

    /** 清理 TTS 文字：去掉不应朗读的标记，保留语气符号 */
    private static String cleanForTts(String text) {
        return text
            // 去掉所有括号内容：（笑）（无奈）（用娇软的声音...）—— TTS 不应朗读
            .replaceAll("[（(][^）)]*[）)]", "")
            // 去掉方括号 emoji 代码
            .replaceAll("\\[.*?\\]", "")
            // 去掉 Markdown 标记
            .replaceAll("\\*\\*|__|\\*", "")
            .replaceAll(" {2,}", " ")
            .strip();
        // 注意：保留 ～ 和 ~ ，它们影响 TTS 的语调和停顿，让语音更自然
    }
}
