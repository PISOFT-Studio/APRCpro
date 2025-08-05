package com.example.anti_politically_related_content;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import okhttp3.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class Main extends JavaPlugin implements Listener, CommandExecutor {
    private boolean aiEnabled = true;
    private String primaryApiKey;
    private String primaryModel;
    private String primaryApiUrl;
    private String backupApiKey;
    private String backupModel;
    private String backupApiUrl;
    private String prompt;
    private String apiType;
    private final OkHttpClient httpClient = new OkHttpClient();
    private FileConfiguration config;
    private long lastFailureTime = 0;
    private static final long FAILURE_COOLDOWN_MS = 5 * 1000;
    private Map<String, PlayerViolationRecord> violationRecords = new HashMap<>();

    private static class PlayerViolationRecord {
        int count;
        LocalDate date;

        PlayerViolationRecord() {
            this.count = 0;
            this.date = LocalDate.now();
        }

        boolean incrementAndCheckBan() {
            if (!date.equals(LocalDate.now())) {
                count = 0;
                date = LocalDate.now();
            }
            count++;
            return count >= 5;
        }
    }

    @Override
    public void onEnable() {
        // 创建配置文件夹和文件
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }
        File configFile = new File(getDataFolder(), "config.yml");

        // 初始化配置文件
        if (!configFile.exists()) {
            try {
                configFile.createNewFile();
                config = YamlConfiguration.loadConfiguration(configFile);
                config.options().header("这是APRC插件配置文件\n" +
                        "默认主API地址为openrouter，备用API为硅基流动\n" +
                        "默认模型均为免费模型\n" +
                        "请按要求填写API Key和必要配置");
                config.set("api_type", "openai");
                config.set("prompt", "请严格判断以下内容是否包含涉政言论（目的不明显不算），只回答Yes或No。不要分析、解释，不要判断是否包含脏话：");
                config.set("primary.api_key", "在此输入主API Key");
                config.set("primary.model", "deepseek/deepseek-chat-v3-0324:free");
                config.set("primary.api_url", "https://openrouter.ai/api/v1/chat/completions");
                config.set("backup.api_key", "在此输入备用API Key");
                config.set("backup.model", "deepseek-ai/DeepSeek-R1-0528-Qwen3-8B");
                config.set("backup.api_url", "https://api.siliconflow.cn/v1/chat/completions");
                config.save(configFile);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "无法创建配置文件", e);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        // 加载配置
        reloadConfig();

        // 验证API密钥
        if (primaryApiKey == null || primaryApiKey.trim().isEmpty()) {
            getLogger().severe("主API密钥未配置！插件已禁用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("aprc") != null) {
            getCommand("aprc").setExecutor(this);
        } else {
            getLogger().severe("命令 'aprc' 未在 plugin.yml 中定义，命令功能不可用！");
        }

        getLogger().info("Anti-Politically Related Content 插件已启用");
    }

    @Override
    public void reloadConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        config = YamlConfiguration.loadConfiguration(configFile);
        apiType = config.getString("api_type", "openai").toLowerCase();
        prompt = config.getString("prompt", "请严格判断以下内容是否包含涉政言论（目的不明显不算），只回答Yes或No。不要分析、解释，不要判断是否包含脏话：");
        primaryApiKey = config.getString("primary.api_key");
        primaryModel = config.getString("primary.model", "deepseek/deepseek-chat-v3-0324:free");
        primaryApiUrl = config.getString("primary.api_url", "https://openrouter.ai/api/v1/chat/completions");
        backupApiKey = config.getString("backup.api_key");
        backupModel = config.getString("backup.model", "deepseek-ai/DeepSeek-R1-0528-Qwen3-8B");
        backupApiUrl = config.getString("backup.api_url", "https://api.siliconflow.cn/v1/chat/completions");
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!aiEnabled) return;
        handleContentCheck(event.getMessage(), event.getPlayer());
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!aiEnabled) return;
        handleContentCheck(event.getMessage(), event.getPlayer());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!aiEnabled) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                boolean isPolitical = checkPoliticalContent(player.getName(), true);
                if (isPolitical) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (player.isOnline()) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                    "say " + player.getName() + " 因ID违规被APRC封禁");
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                    "ban " + player.getName() + " 您已被封禁：违规的ID");
                            getLogger().log(Level.INFO, "已封禁玩家 " + player.getName() + " 因违规ID");
                            logViolation(player.getName(), player.getName(), "ID涉政");
                        }
                    });
                }
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "调用API失败", e);
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("aprc.admin")) {
            sender.sendMessage("§c你没有权限使用此命令");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§aAPRC状态: " + (aiEnabled ? "§a启用" : "§c禁用"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "on":
                aiEnabled = true;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "say APRC AI审核已开启");
                sender.sendMessage("§a已启用APRC AI审核");
                getLogger().info(sender.getName() + " 已启用APRC AI审核");
                break;
            case "off":
                aiEnabled = false;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "say APRC AI审核已关闭");
                sender.sendMessage("§c已禁用APRC AI审核");
                getLogger().info(sender.getName() + " 已禁用APRC AI审核");
                break;
            case "test":
                testAPI(sender);
                break;
            case "reload":
                reloadConfig();
                sender.sendMessage("§a配置已重新加载");
                getLogger().info(sender.getName() + " 重新加载了配置文件");
                break;
            case "version":
                sender.sendMessage("§aAPRC Pro v1.0.1 - By PISOFT");
                sender.sendMessage("§e项目地址：https://github.com/PISOFT-Studio/APRCpro");
                sender.sendMessage("§c基于APRC 4.0-RELEASE");
                sender.sendMessage("§b原作者：IntelliJ IDEA 又称 Shabby-666");
                sender.sendMessage("§b原作者项目地址：https://github.com/Shabby-666/APRC");
                break;
            default:
                sender.sendMessage("§c用法: /aprc [on|off|test|reload|version]");
        }
        return true;
    }

    private String getStatusMessage(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 429 -> "速率限制";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Unknown";
        };
    }

    private void testAPI(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // 测试主API
                JsonObject requestBody = new JsonObject();
                JsonArray messages = new JsonArray();
                JsonObject systemMessage = new JsonObject();
                systemMessage.addProperty("role", "system");
                systemMessage.addProperty("content", "请回复'测试成功'");
                messages.add(systemMessage);

                JsonObject userMessage = new JsonObject();
                userMessage.addProperty("role", "user");
                userMessage.addProperty("content", "测试连接");
                messages.add(userMessage);

                if (apiType.equals("gemini")) {
                    JsonObject payload = new JsonObject();
                    JsonArray contents = new JsonArray();
                    JsonObject content = new JsonObject();
                    content.addProperty("role", "user");
                    JsonArray parts = new JsonArray();
                    JsonObject part = new JsonObject();
                    part.addProperty("text", "请回复'测试成功'");
                    parts.add(part);
                    content.add("parts", parts);
                    contents.add(content);
                    payload.add("contents", contents);

                    Request request = new Request.Builder()
                            .url(primaryApiUrl + "?key=" + primaryApiKey)
                            .header("Content-Type", "application/json")
                            .post(RequestBody.create(payload.toString(), MediaType.get("application/json")))
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful()) {
                            String responseBody = response.body().string();
                            JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
                            String result = jsonResponse.getAsJsonArray("candidates")
                                    .get(0).getAsJsonObject()
                                    .getAsJsonObject("content")
                                    .getAsJsonArray("parts")
                                    .get(0).getAsJsonObject()
                                    .get("text").getAsString()
                                    .trim();

                            if (result.contains("测试成功")) {
                                Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("§a主API测试成功: 200 OK"));
                                getLogger().info("主API测试成功");
                            } else {
                                Bukkit.getScheduler().runTask(this, () ->
                                        sender.sendMessage("§c主API测试失败: 预期'测试成功'但得到'" + result + "'"));
                                getLogger().warning("主API测试失败: " + result);
                            }
                        } else {
                            Bukkit.getScheduler().runTask(this, () ->
                                    sender.sendMessage("§c主API测试失败: " + response.code() + " " + getStatusMessage(response.code())));
                            getLogger().warning("主API测试失败: " + response.code());
                        }
                    }
                } else {
                    requestBody.addProperty("model", primaryModel);
                    requestBody.add("messages", messages);
                    requestBody.addProperty("max_tokens", 10);

                    Request request = new Request.Builder()
                            .url(primaryApiUrl)
                            .header("Authorization", "Bearer " + primaryApiKey)
                            .header("Content-Type", "application/json")
                            .post(RequestBody.create(requestBody.toString(), MediaType.get("application/json")))
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful()) {
                            String responseBody = response.body().string();
                            JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
                            String result = jsonResponse.getAsJsonArray("choices")
                                    .get(0).getAsJsonObject()
                                    .getAsJsonObject("message")
                                    .get("content").getAsString()
                                    .trim();

                            if (result.contains("测试成功")) {
                                Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("§a主API测试成功: 200 OK"));
                                getLogger().info("主API测试成功");
                            } else {
                                Bukkit.getScheduler().runTask(this, () ->
                                        sender.sendMessage("§c主API测试失败: 预期'测试成功'但得到'" + result + "'"));
                                getLogger().warning("主API测试失败: " + result);
                            }
                        } else {
                            Bukkit.getScheduler().runTask(this, () ->
                                    sender.sendMessage("§c主API测试失败: " + response.code() + " " + getStatusMessage(response.code())));
                            getLogger().warning("主API测试失败: " + response.code());
                        }
                    }
                }

                // 测试备用API（如果配置了）
                if (backupApiKey != null && !backupApiKey.trim().isEmpty()) {
                    requestBody = new JsonObject();
                    messages = new JsonArray();
                    systemMessage = new JsonObject();
                    systemMessage.addProperty("role", "system");
                    systemMessage.addProperty("content", "请回复'测试成功'");
                    messages.add(systemMessage);

                    userMessage = new JsonObject();
                    userMessage.addProperty("role", "user");
                    userMessage.addProperty("content", "测试连接");
                    messages.add(userMessage);

                    if (apiType.equals("gemini")) {
                        JsonObject payload = new JsonObject();
                        JsonArray contents = new JsonArray();
                        JsonObject content = new JsonObject();
                        content.addProperty("role", "user");
                        JsonArray parts = new JsonArray();
                        JsonObject part = new JsonObject();
                        part.addProperty("text", "请回复'测试成功'");
                        parts.add(part);
                        content.add("parts", parts);
                        contents.add(content);
                        payload.add("contents", contents);

                        Request request = new Request.Builder()
                                .url(backupApiUrl + "?key=" + backupApiKey)
                                .header("Content-Type", "application/json")
                                .post(RequestBody.create(payload.toString(), MediaType.get("application/json")))
                                .build();

                        try (Response response = httpClient.newCall(request).execute()) {
                            if (response.isSuccessful()) {
                                String responseBody = response.body().string();
                                JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
                                String result = jsonResponse.getAsJsonArray("candidates")
                                        .get(0).getAsJsonObject()
                                        .getAsJsonObject("content")
                                        .getAsJsonArray("parts")
                                        .get(0).getAsJsonObject()
                                        .get("text").getAsString()
                                        .trim();

                                if (result.contains("测试成功")) {
                                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("§a备用API测试成功: 200 OK"));
                                    getLogger().info("备用API测试成功");
                                } else {
                                    Bukkit.getScheduler().runTask(this, () ->
                                            sender.sendMessage("§c备用API测试失败: 预期'测试成功'但得到'" + result + "'"));
                                    getLogger().warning("备用API测试失败: " + result);
                                }
                            } else {
                                Bukkit.getScheduler().runTask(this, () ->
                                        sender.sendMessage("§c备用API测试失败: " + response.code() + " " + getStatusMessage(response.code())));
                                getLogger().warning("备用API测试失败: " + response.code());
                            }
                        }
                    } else {
                        requestBody.addProperty("model", backupModel);
                        requestBody.add("messages", messages);
                        requestBody.addProperty("max_tokens", 10);

                        Request request = new Request.Builder()
                                .url(backupApiUrl)
                                .header("Authorization", "Bearer " + backupApiKey)
                                .header("Content-Type", "application/json")
                                .post(RequestBody.create(requestBody.toString(), MediaType.get("application/json")))
                                .build();

                        try (Response response = httpClient.newCall(request).execute()) {
                            if (response.isSuccessful()) {
                                String responseBody = response.body().string();
                                JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
                                String result = jsonResponse.getAsJsonArray("choices")
                                        .get(0).getAsJsonObject()
                                        .getAsJsonObject("message")
                                        .get("content").getAsString()
                                        .trim();

                                if (result.contains("测试成功")) {
                                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("§a备用API测试成功: 200 OK"));
                                    getLogger().info("备用API测试成功");
                                } else {
                                    Bukkit.getScheduler().runTask(this, () ->
                                            sender.sendMessage("§c备用API测试失败: 预期'测试成功'但得到'" + result + "'"));
                                    getLogger().warning("备用API测试失败: " + result);
                                }
                            } else {
                                Bukkit.getScheduler().runTask(this, () ->
                                        sender.sendMessage("§c备用API测试失败: " + response.code() + " " + getStatusMessage(response.code())));
                                getLogger().warning("备用API测试失败: " + response.code());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("§cAPI测试失败: " + e.getMessage()));
                getLogger().log(Level.SEVERE, "API测试失败", e);
            }
        });
    }

    private void handleContentCheck(String content, Player player) {
        if (!aiEnabled || content == null || player == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                boolean isPolitical = checkPoliticalContent(content, false);
                if (isPolitical) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (player.isOnline()) {
                            String playerName = player.getName();
                            violationRecords.putIfAbsent(playerName, new PlayerViolationRecord());
                            boolean shouldBan = violationRecords.get(playerName).incrementAndCheckBan();

                            if (shouldBan) {
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                        "say " + playerName + " 因24小时内发送5条涉政消息被APRC封禁");
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                        "ban " + playerName + " 您已被封禁：24小时内发送5条违规消息");
                                getLogger().log(Level.INFO, "已封禁玩家 " + playerName + "，因24小时内发送5条涉政消息");
                                logViolation(playerName, content, "24小时内第5条涉政消息");
                                violationRecords.remove(playerName);
                            } else {
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                        "say " + playerName + " 由于发送涉政消息被APRC踢出");
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                        "kick " + playerName + " 您已被踢出：发送违规消息");
                                getLogger().log(Level.INFO, "踢出玩家 " + playerName + "，内容：" + content);
                                logViolation(playerName, content, "聊天涉政，第" + violationRecords.get(playerName).count + "次");
                            }
                        }
                    });
                }
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "调用API失败", e);
            }
        });
    }

    private boolean checkPoliticalContent(String message, boolean isPlayerId) throws IOException {
        if (message == null || message.trim().isEmpty()) return false;

        long now = System.currentTimeMillis();
        if (now - lastFailureTime < FAILURE_COOLDOWN_MS) {
            getLogger().warning("AI 审核处于冷却中，跳过检测");
            return false;
        }

        // 先尝试主API
        try {
            Request request = buildRequest(message, primaryApiKey, primaryModel, primaryApiUrl, isPlayerId);
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "{}";
                if (response.isSuccessful()) {
                    String result = parseResponse(responseBody);
                    getLogger().log(Level.INFO, "主API判断 " + message + " 是否违规：" + result);
                    return result.equalsIgnoreCase("Yes");
                } else {
                    getLogger().log(Level.WARNING, "主API请求失败，状态码: " + response.code() + "，返回内容: " + responseBody);
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "主API调用失败，尝试备用API", e);
        }

        // 主API失败时尝试备用API（如果配置了）
        if (backupApiKey != null && !backupApiKey.trim().isEmpty()) {
            try {
                Request request = buildRequest(message, backupApiKey, backupModel, backupApiUrl, isPlayerId);
                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body() != null ? response.body().string() : "{}";
                    if (response.isSuccessful()) {
                        String result = parseResponse(responseBody);
                        getLogger().log(Level.INFO, "备用API判断 " + message + " 是否违规：" + result);
                        return result.equalsIgnoreCase("Yes");
                    } else {
                        lastFailureTime = System.currentTimeMillis();
                        getLogger().log(Level.SEVERE, "备用API请求失败，状态码: " + response.code() + "，返回内容: " + responseBody);
                        throw new IOException("备用API请求失败，状态码: " + response.code());
                    }
                }
            } catch (Exception e) {
                lastFailureTime = System.currentTimeMillis();
                getLogger().log(Level.SEVERE, "备用API调用失败", e);
                throw new IOException("备用API调用失败", e);
            }
        }

        return false;
    }

    private Request buildRequest(String message, String apiKey, String model, String apiUrl, boolean isPlayerId) {
        if (apiType.equals("gemini")) {
            JsonObject payload = new JsonObject();
            JsonArray contents = new JsonArray();
            JsonObject content = new JsonObject();
            content.addProperty("role", "user");
            JsonArray parts = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("text", prompt + (isPlayerId ? "玩家ID: " + message : message));
            parts.add(part);
            content.add("parts", parts);
            contents.add(content);
            payload.add("contents", contents);

            return new Request.Builder()
                    .url(apiUrl + "?key=" + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(payload.toString(), MediaType.get("application/json")))
                    .build();
        } else {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);
            JsonArray messages = new JsonArray();
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", prompt);
            messages.add(systemMessage);
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", isPlayerId ? "玩家ID: " + message : message);
            messages.add(userMessage);
            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", 3);

            return new Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody.toString(), MediaType.get("application/json")))
                    .build();
        }
    }

    private String parseResponse(String responseBody) throws IOException {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            if (apiType.equals("gemini")) {
                if (!json.has("candidates") || json.getAsJsonArray("candidates").isEmpty()) {
                    throw new IOException("Gemini API返回格式错误: 无candidates字段");
                }
                JsonObject candidate = json.getAsJsonArray("candidates").get(0).getAsJsonObject();
                if (!candidate.has("content") || !candidate.getAsJsonObject("content").has("parts")) {
                    throw new IOException("Gemini API返回格式错误: 无content或parts字段");
                }
                return candidate.getAsJsonObject("content")
                        .getAsJsonArray("parts")
                        .get(0).getAsJsonObject()
                        .get("text").getAsString()
                        .trim();
            } else {
                if (!json.has("choices") || json.getAsJsonArray("choices").isEmpty()) {
                    throw new IOException("OpenAI API返回格式错误: 无choices字段");
                }
                return json.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString()
                        .trim();
            }
        } catch (Exception e) {
            throw new IOException("解析API响应失败", e);
        }
    }

    private void logViolation(String playerName, String message, String reason) {
        try {
            Path logDir = Paths.get(getDataFolder().getAbsolutePath(), "logs");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve(LocalDate.now() + ".log");

            String logEntry = String.format("[%s] 玩家：%s | 原因：%s | 内容：%s%n",
                    LocalTime.now().withNano(0), playerName, reason, message);

            Files.write(logFile, logEntry.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().warning("记录违规日志失败：" + e.getMessage());
        }
    }
}
