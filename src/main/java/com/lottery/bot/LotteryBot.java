// bot/LotteryBot.java
package com.lottery.bot;

import com.lottery.bot.config.BotConfig;
import com.lottery.bot.model.Lottery;
import com.lottery.bot.model.LotteryTemplate;

import com.lottery.bot.model.Participant;
import com.lottery.bot.service.LotteryService;
import com.lottery.bot.service.LotteryTemplateService;
import com.lottery.bot.service.LocalizationService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.lottery.bot.service.LotteryService.DrawStatus.*;

@Slf4j
@Component
public class LotteryBot extends TelegramLongPollingBot {

    private final BotConfig botConfig;
    private final LotteryService lotteryService;
    private final LotteryTemplateService templateService;
    private final LocalizationService localizationService;

    // 多步骤创建抽奖状态机（用户ID -> 当前步骤）
    private final Map<Long, CreateLotterySession> createSessions = new ConcurrentHashMap<>();
    private final Map<Long, EditTemplateSession> editTemplateSessions = new ConcurrentHashMap<>();

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public LotteryBot(BotConfig botConfig, LotteryService lotteryService, LotteryTemplateService templateService, LocalizationService localizationService) {
        super(botConfig.getToken());
        this.botConfig = botConfig;
        this.lotteryService = lotteryService;
        this.templateService = templateService;
        this.localizationService = localizationService;
        log.info("LotteryBot initialized with username: {}", botConfig.getUsername());
    }
    // ==================== 群聊权限验证 ====================


    private boolean isAdminGroup(Long chatId) {
        return botConfig.getAdminGroupId() != null && botConfig.getAdminGroupId().equals(chatId);
    }

    private boolean isLotteryGroup(Long chatId) {
        return botConfig.getLotteryGroupIds().contains(chatId);
    }

    private boolean isPrivateChat(Long chatId) {
        return chatId > 0;
    }

    private boolean isAdminUser(Long userId) {
        return botConfig.getAdminId() != null && botConfig.getAdminId().equals(userId);
    }


    @PostConstruct
    public void init() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("Telegram Bot [{}] started successfully!", getBotUsername());

            if (!isAdminGroupHasPinnedLotteryMessage()) {
                sendHelpToAdminGroup();
            }
        } catch (TelegramApiException e) {
            log.error("Failed to start Telegram Bot", e);
        }
    }

    private boolean isAdminGroupHasPinnedLotteryMessage() {
        Long adminGroupId = botConfig.getAdminGroupId();
        if (adminGroupId == null) {
            return false;
        }
        try {
            String token = botConfig.getToken();
            String urlStr = "https://api.telegram.org/bot" + token + "/getChat?chat_id=" + adminGroupId;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            conn.disconnect();
            
            String resp = response.toString();
            if (resp.contains("\"ok\":true") && resp.contains("\"pinned_message\"")) {
                int textStart = resp.indexOf("\"text\":\"");
                if (textStart != -1) {
                    int textEnd = resp.indexOf("\",\"", textStart);
                    if (textEnd == -1) textEnd = resp.indexOf("\"}", textStart);
                    if (textEnd != -1) {
                        String pinnedText = resp.substring(textStart + 8, textEnd);
                        return pinnedText.contains("抽奖机器人");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error checking pinned message: {}", e.getMessage());
        }
        return false;
    }

    private void sendHelpToAdminGroup() {
        Long adminGroupId = botConfig.getAdminGroupId();
        if (adminGroupId == null) {
            log.warn("Admin group ID not configured, skipping help message");
            return;
        }
        if(true){
            return;
        }

//                *模板管理（在管理群或私聊中使用）：*
//                /tpl - 创建抽奖模板
//                /tpllist - 查看模板列表
//                /tpledit [ID] - 编辑模板
//                /tpldel [ID] - 删除模板
//
//                *抽奖管理（在管理群或私聊中使用）：*
//                /startlottery [模板ID] - 开启抽奖（使用模板创建抽奖）
//                /lotterylist - 查看抽奖列表
//                /activate [ID] - 激活指定抽奖（推送到抽奖群并置顶）
//                /deactivate - 停用当前抽奖（取消置顶）
//                /delete [ID] - 删除抽奖
//                /draw [ID] - 立即开奖（可指定ID）
//                /cancel [ID] - 取消抽奖
//
//                *用户命令（在抽奖群中使用）：*
//                /join - 参与当前抽奖
//                /list - 查看参与者列表
//                /info - 查看当前抽奖信息
//
//                *使用流程：*
//                1. 使用 /tpl 创建抽奖模板
//                2. 使用 /startlottery [模板ID] 开启抽奖
//                3. 使用 /activate [ID] 激活抽奖，推送到抽奖群
//                4. 用户在抽奖群点击 *参与抽奖* 按钮报名
//                5. 使用 /draw 开奖，机器人随机选取获奖者

        String helpText = """
                *Lottery Bot 抽奖机器人 *

                _机器人版本: v1.3.0_
                """;

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("ℹ️ 帮助")
                                .callbackData("main_menu")
                                .build()
                ))
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(adminGroupId.toString())
                .text(escapeMarkdown(helpText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(keyboard)
                .build();

        try {
            Message sent = execute(msg);
            pinChatMessage(adminGroupId, sent.getMessageId());
            log.info("Help message sent and pinned in admin group {}", adminGroupId);
        } catch (TelegramApiException e) {
            log.error("Failed to send help message to admin group: {}", e.getMessage());
        }
    }

    private void pinChatMessage(Long chatId, Integer messageId) {
        try {
            execute(PinChatMessage.builder()
                    .chatId(chatId.toString())
                    .messageId(messageId)
                    .build());
        } catch (TelegramApiException e) {
            log.debug("Failed to pin message in chat {}: {}", chatId, e.getMessage());
        }
    }

    @Override
    public String getBotUsername() {
        return botConfig.getUsername();
    }

    // ==================== 消息路由 ====================

    @Override
    public void onUpdateReceived(Update update) {
            log.debug("Received update: {}", update);

        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            } else if (update.hasMessage()) {
                Message message = update.getMessage();
                Long chatId = message.getChatId();
                
                if (isPrivateChat(chatId) && !isAdminUser(message.getFrom().getId())) {
                    return;
                }
                
                if (message.hasText()) {
                    String text = message.getText().trim();
                    if (text.startsWith("/")) {
                        handleCommand(message);
                    } else {
                        handleTextInput(message);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing update: {}", e.getMessage(), e);
        }
    }

    // ==================== 命令处理 ====================

    private void handleCommand(Message message) {
        String text = message.getText();

        String command = text.split(" ")[0].toLowerCase()
                .replace("@" + botConfig.getUsername().toLowerCase(), "");

        Long chatId = message.getChatId();

        if (isLotteryGroup(chatId)) {
            if (!command.equals("/join")
//                    && !command.equals("/list") && !command.equals("/info")
//                    && !command.equals("/start") && !command.equals("/help")
            ) {
                return;
            }
        }

        Long userId = message.getFrom().getId();

        // 验证群聊权限
        switch (command) {
            case "/newlottery" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    sendMarkdownMessage(chatId, "创建抽奖模板只能在管理群或私聊中使用。");
                    return;
                }
                startCreateLottery(message);
            }
            case "/tpl", "/template" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    log.info("{} is not admin group or private chat", chatId);
                    sendMarkdownMessage(chatId, "创建抽奖模板只能在管理群或私聊中使用。");
                    return;
                }
                startCreateTemplate(message);
            }
            case "/tpllist" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    sendMarkdownMessage(chatId, "该命令只能在管理群或私聊中使用。");
                    return;
                }
                handleTemplateListCommand(message);
            }
            case "/tpledit" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    sendMarkdownMessage(chatId, "编辑模板只能在管理群或私聊中使用。");
                    return;
                }
                startEditTemplate(message);
            }
            case "/tpldel" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    sendMarkdownMessage(chatId, "删除模板只能在管理群或私聊中使用。");
                    return;
                }
                handleDeleteTemplateCommand(message);
            }
            case "/startlottery" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    sendMarkdownMessage(chatId, "开启抽奖只能在管理群或私聊中使用。");
                    return;
                }
                handleStartLotteryCommand(message);
            }
            case "/lotterylist" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    sendMarkdownMessage(chatId, "该命令只能在管理群或私聊中使用。");
                    return;
                }
                handleLotteryListCommand(message);
            }
            case "/activate" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    sendMarkdownMessage(chatId, "激活抽奖只能在管理群或私聊中使用。");
                    return;
                }
                handleActivateCommand(message);
            }
            case "/deactivate" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    sendMarkdownMessage(chatId, "停用抽奖只能在管理群或私聊中使用。");
                    return;
                }
                handleDeactivateCommand(message);
            }
            case "/delete" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    sendMarkdownMessage(chatId, "删除抽奖只能在管理群或私聊中使用。");
                    return;
                }
                handleDeleteCommand(message);
            }
            case "/join" -> {
                if (!isLotteryGroup(chatId)) {
                    log.info("参与抽奖只能在抽奖群中使用 {}",chatId);
                    sendMarkdownMessage(chatId, "Joining lottery is only available in lottery groups.");
                    return;
                }
                handleJoinCommand(message);
            }
            case "/draw" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    sendMarkdownMessage(chatId, "开奖操作只能在管理群或私聊中使用。");
                    return;
                }
                handleDrawCommand(message);
            }
            case "/cancel" -> {
                if (!isAdminGroup(chatId) && !isPrivateChat(chatId)) {
                    sendMarkdownMessage(chatId, "取消抽奖只能在管理群或私聊中使用。");
                    return;
                }
                handleCancelCommand(message);
            }
            case "/list" -> {
                if (!isLotteryGroup(chatId)) {
                    sendMarkdownMessage(chatId, "查看参与者列表只能在抽奖群中使用。");
                    return;
                }
                handleListCommand(message);
            }
            case "/info" -> {
                if (!isLotteryGroup(chatId)) {
                    sendMarkdownMessage(chatId, "查看抽奖信息只能在抽奖群中使用。");
                    return;
                }
                handleInfoCommand(message);
            }
            case "/start", "/help" -> {
                if (isPrivateChat(message.getChatId()) || isAdminGroup(message.getChatId())) {
                    sendMainMenu(message);
                } else {
                    sendHelp(message);
                }
            }
            default -> { /* 忽略未知命令 */ }
        }
    }

    // ==================== /help ====================

    private void sendHelp(Message message) {
        String helpText = """
                *== Lottery Bot 抽奖机器人 ==*

                *模板管理（在管理群或私聊中使用）：*
                /tpl - 创建抽奖模板
                /tpllist - 查看模板列表
                /tpledit [ID] - 编辑模板
                /tpldel [ID] - 删除模板

                *抽奖管理（在管理群或私聊中使用）：*
                /startlottery [模板ID] - 开启抽奖（使用模板创建抽奖）
                /lotterylist - 查看抽奖列表
                /activate [ID] - 激活指定抽奖（推送到抽奖群并置顶）
                /deactivate - 停用当前抽奖（取消置顶）
                /delete [ID] - 删除抽奖
                /draw [ID] - 立即开奖（可指定ID）
                /cancel [ID] - 取消抽奖

                *用户命令（在抽奖群中使用）：*
                /join - 参与当前抽奖
                /list - 查看参与者列表
                /info - 查看当前抽奖信息

                *使用流程：*
                1. 使用 /tpl 创建抽奖模板
                2. 使用 /startlottery [模板ID] 开启抽奖
                3. 使用 /activate [ID] 激活抽奖，推送到抽奖群
                4. 用户在抽奖群点击 *参与抽奖* 按钮报名
                5. 使用 /draw 开奖，机器人随机选取获奖者

                _机器人版本: v1.3.0_
                """;

        sendMarkdownMessage(message.getChatId(), helpText);
    }

    private void sendMainMenu(Message message) {
        Long chatId = message.getChatId();
        boolean isLotteryGrp = isLotteryGroup(chatId);
        boolean isAdminGrp = isAdminGroup(chatId);
        boolean isPrivate = isPrivateChat(chatId);

        String menuText;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if (isLotteryGrp) {
            menuText = """
                    *🎯 Lottery Bot*

                    点击下方按钮参与抽奖！
                    """;

            rows.add(List.of(
                    InlineKeyboardButton.builder()
                            .text("🎰 参与抽奖")
                            .callbackData("menu_join")
                            .build()
            ));
        } else if (isAdminGrp || isPrivate) {
            menuText = """
                    *🎯 Lottery Bot 主菜单*

                    请选择功能类别：

                    📋 *模板管理*
                    创建、编辑、删除抽奖模板

                    🎁 *抽奖管理*
                    开启、激活、开奖抽奖活动
                    """;

            rows.add(List.of(
                    InlineKeyboardButton.builder()
                            .text("📋 模板管理")
                            .callbackData("menu_template")
                            .build(),
                    InlineKeyboardButton.builder()
                            .text("🎁 抽奖管理")
                            .callbackData("menu_lottery")
                            .build()
            ));

            if (isPrivate) {
                rows.add(List.of(
                        InlineKeyboardButton.builder()
                                .text("👥 用户功能")
                                .callbackData("menu_user")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("ℹ️ 帮助说明")
                                .callbackData("menu_help")
                                .build()
                ));
            }
        } else {
            return;
        }

        InlineKeyboardMarkup keyboard;
        if (rows.size() == 1) {
            keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(rows.get(0))
                    .build();
        } else if (rows.size() == 2) {
            keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(rows.get(0))
                    .keyboardRow(rows.get(1))
                    .build();
        } else {
            keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(rows.get(0))
                    .keyboardRow(rows.get(1))
                    .keyboardRow(rows.get(2))
                    .build();
        }

        SendMessage msg = SendMessage.builder()
                .chatId(message.getChatId().toString())
                .text(escapeMarkdown(menuText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(keyboard)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("发送主菜单失败", e);
        }
    }

    private void sendTemplateMenu(Long chatId) {
        String menuText = """
                *📋 模板管理*

                请选择操作：
                """;

        // 获取所有模板
        List<LotteryTemplate> templates = templateService.getAllTemplates();

        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        // 顶部按钮：创建模板和刷新列表
        keyboard.add(List.of(
                InlineKeyboardButton.builder()
                        .text("🆕 创建模板")
                        .callbackData("cmd_tpl")
                        .build(),
                InlineKeyboardButton.builder()
                        .text("🔄 刷新列表")
                        .callbackData("cmd_tpllist")
                        .build()
        ));

        // 中间：所有模板列表（每行一个按钮）
        for (LotteryTemplate template : templates) {
            keyboard.add(List.of(
                    InlineKeyboardButton.builder()
                            .text(template.getId() + ". " + truncate(template.getTitle(), 20))
                            .callbackData("tpl_detail:" + template.getId())
                            .build()
            ));
        }

        // 底部：返回主菜单按钮
        keyboard.add(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ 返回主菜单")
                        .callbackData("main_menu")
                        .build()
        ));

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(keyboard)
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(menuText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(markup)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("发送模板菜单失败", e);
        }
    }

    private void sendLotteryMenu(Long chatId) {
        List<Lottery> allLotteries = lotteryService.getAllLotteries();
        List<Lottery> activeLotteries = allLotteries.stream()
                .filter(l -> l.getStatus() == Lottery.LotteryStatus.ACTIVE)
                .collect(Collectors.toList());

        String menuText = """
                *🎁 抽奖管理菜单*

                请选择抽奖操作：
        
                """;

        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        if (!activeLotteries.isEmpty()) {
            for (Lottery lottery : activeLotteries) {
                long participantCount = lotteryService.getParticipantCount(lottery.getId());
                String activeText = String.format("🎯 ID:%d %s (%d人)",
                        lottery.getId(), truncate(lottery.getTitle(), 12), participantCount);
                keyboard.add(List.of(
                        InlineKeyboardButton.builder()
                                .text(activeText)
                                .callbackData("lottery_detail:" + lottery.getId())
                                .build()
                ));
            }
        }

        keyboard.add(List.of(
                InlineKeyboardButton.builder()
                        .text("🚀 开启抽奖")
                        .callbackData("cmd_startlottery")
                        .build(),
                InlineKeyboardButton.builder()
                        .text("📋 历史抽奖")
                        .callbackData("cmd_lotteryhistory")
                        .build()
        ));

        keyboard.add(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ 返回主菜单")
                        .callbackData("main_menu")
                        .build()
        ));

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(keyboard)
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(menuText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(markup)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("发送抽奖菜单失败", e);
        }
    }

    private void handleLotteryHistoryCommand(Message message) {
        Long chatId = message.getChatId();
        List<Lottery> allLotteries = lotteryService.getAllLotteries();
        List<Lottery> historyLotteries = allLotteries.stream()
                .filter(l -> l.getStatus() != Lottery.LotteryStatus.ACTIVE)
                .sorted(Comparator.comparing(Lottery::getCreatedAt).reversed())
                .collect(Collectors.toList());

        if (historyLotteries.isEmpty()) {
            sendMarkdownMessage(chatId, "暂无历史抽奖记录。");
            return;
        }

        String menuText = "*📋 历史抽奖*\n\n选择抽奖查看详情：";

        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        for (Lottery l : historyLotteries) {
            String status = switch (l.getStatus()) {
                case DRAWN -> "🏁";
                case CANCELLED -> "❌";
                default -> "⚪";
            };
            long participantCount = lotteryService.getParticipantCount(l.getId());
            String createdTime = l.getCreatedAt() != null ? l.getCreatedAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) : "";
            String buttonText = String.format("%s ID:%d %s (%d人|%s)",
                    status, l.getId(), truncate(l.getTitle(), 10), participantCount, createdTime);
            keyboard.add(List.of(
                    InlineKeyboardButton.builder()
                            .text(buttonText)
                            .callbackData("lottery_detail:" + l.getId())
                            .build()
            ));
        }

        keyboard.add(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ 返回")
                        .callbackData("menu_lottery")
                        .build()
        ));

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(keyboard)
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(menuText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(markup)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("发送模板菜单失败", e);
        }
    }

    private void sendUserMenu(Long chatId) {
        String menuText = """
                *👥 用户功能菜单*

                请选择用户功能：

                • /join —— 参与当前抽奖
                • /list —— 查看参与者列表
                • /info —— 查看当前抽奖信息
                """;

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(
                        List.of(
                                InlineKeyboardButton.builder()
                                        .text("🎉 参与抽奖")
                                        .callbackData("cmd_join")
                                        .build(),
                                InlineKeyboardButton.builder()
                                        .text("👥 查看参与者")
                                        .callbackData("cmd_list")
                                        .build()
                        )
                )
                .keyboardRow(
                        List.of(
                                InlineKeyboardButton.builder()
                                        .text("ℹ️ 抽奖信息")
                                        .callbackData("cmd_info")
                                        .build(),
                                InlineKeyboardButton.builder()
                                        .text("⬅️ 返回主菜单")
                                        .callbackData("main_menu")
                                        .build()
                        )
                )
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(menuText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(keyboard)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("发送用户菜单失败", e);
        }
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        Long chatId = callbackQuery.getMessage().getChatId();

        try {
            if (data.startsWith("tpl_list")) {
                sendTemplateListMenu(chatId);
            } else if (data.startsWith("tpl_detail:")) {
                Long templateId = Long.parseLong(data.substring(11));
                sendTemplateDetailMenu(chatId, templateId);
            } else if (data.startsWith("tpl_edit:")) {
                Long templateId = Long.parseLong(data.substring(9));
                // 直接开始编辑模板交互
                Message message = callbackQuery.getMessage();
                Message newMessage = new Message();
                newMessage.setChat(message.getChat());
                newMessage.setText("/tpledit " + templateId);
                newMessage.setFrom(callbackQuery.getFrom());
                startEditTemplate(newMessage);
            } else if (data.startsWith("tpl_del:")) {
                Long templateId = Long.parseLong(data.substring(8));
                templateService.deleteTemplate(templateId, callbackQuery.getFrom().getId(),
                        callbackQuery.getFrom().getId().equals(botConfig.getAdminId()));
                sendMarkdownMessage(chatId, "模板已删除！");
                sendTemplateListMenu(chatId);
            } else if (data.startsWith("start_lottery:")) {
                // 选择模板开启抽奖
                Long templateId = Long.parseLong(data.substring(14));
                handleStartLotteryFromTemplate(callbackQuery, templateId);
            } else if (data.startsWith("confirm_start_lottery:")) {
                Long templateId = Long.parseLong(data.substring(22));
                handleConfirmStartLottery(callbackQuery, templateId);
            } else if (data.startsWith("lottery_detail:")) {
                Long lotteryId = Long.parseLong(data.substring(15));
                sendLotteryDetailMenu(chatId, lotteryId);
            } else if (data.startsWith("lottery_activate:")) {
                Long lotteryId = Long.parseLong(data.substring(17));
                handleLotteryActivate(callbackQuery, lotteryId);
            } else if (data.startsWith("lottery_deactivate:")) {
                Long lotteryId = Long.parseLong(data.substring(19));
                handleLotteryDeactivate(callbackQuery, lotteryId);
            } else if (data.startsWith("lottery_draw:")) {
                Long lotteryId = Long.parseLong(data.substring(13));
                handleLotteryDraw(callbackQuery, lotteryId);
            } else if (data.startsWith("lottery_delete:")) {
                Long lotteryId = Long.parseLong(data.substring(15));
                handleLotteryDelete(callbackQuery, lotteryId);
            } else if (data.startsWith("lottery_prize_distributed:")) {
                Long lotteryId = Long.parseLong(data.substring(26));
                handleLotteryPrizeDistributed(callbackQuery, lotteryId);
            } else {
                switch (data) {
                    case "main_menu" -> sendMainMenu(callbackQuery.getMessage());
                    case "menu_template" -> sendTemplateMenu(chatId);
                    case "menu_lottery" -> sendLotteryMenu(chatId);
                    case "menu_user" -> sendUserMenu(chatId);
                    case "menu_help" -> sendHelp(callbackQuery.getMessage());
                    case "menu_join" -> handleJoinCommand(callbackQuery.getMessage());
                    case "cmd_tpl" -> {
                        // 从回调查询中获取用户信息并触发创建模板
                        Message message = callbackQuery.getMessage();
                        Message newMessage = new Message();
                        newMessage.setChat(message.getChat());
                        newMessage.setText("/tpl");
                        newMessage.setFrom(callbackQuery.getFrom());
                        startCreateTemplate(newMessage);
                    }
                    case "cmd_tpllist" -> {
                        sendTemplateMenu(chatId);
                    }
                    case "cmd_tpledit" -> {
                        sendMarkdownMessage(chatId, "请使用 /tpledit [ID] 命令编辑模板");
                        sendTemplateMenu(chatId);
                    }
                    case "cmd_tpldel" -> {
                        sendMarkdownMessage(chatId, "请使用 /tpldel [ID] 命令删除模板");
                        sendTemplateMenu(chatId);
                    }
                    case "cmd_startlottery" -> {
                        // 显示模板列表供用户选择
                        sendStartLotteryTemplateMenu(chatId);
                    }
                    case "cmd_lotterylist" -> {
                        handleLotteryListCommand(callbackQuery.getMessage());
                    }
                    case "cmd_lotteryhistory" -> {
                        handleLotteryHistoryCommand(callbackQuery.getMessage());
                    }
                    case "cmd_activate" -> {
                        sendMarkdownMessage(chatId, "请使用 /activate [ID] 命令激活抽奖");
                        sendLotteryMenu(chatId);
                    }
                    case "cmd_deactivate" -> {
                        sendMarkdownMessage(chatId, "请使用 /deactivate 命令停用当前抽奖");
                        sendLotteryMenu(chatId);
                    }
                    case "cmd_draw" -> {
                        sendMarkdownMessage(chatId, "请使用 /draw [ID] 命令开奖");
                        sendLotteryMenu(chatId);
                    }
                    case "cmd_delete" -> {
                        // 显示抽奖列表供用户选择删除
                        sendLotteryListForDelete(chatId, callbackQuery);
                    }
                    case "cmd_join" -> {
                        sendMarkdownMessage(chatId, "请使用 /join 命令参与抽奖");
                        sendUserMenu(chatId);
                    }
                    case "cmd_list" -> {
                        // 创建虚拟消息来触发命令处理
                        Message message = callbackQuery.getMessage();
                        Message newMessage = new Message();
                        newMessage.setChat(message.getChat());
                        newMessage.setText("/list");
                        newMessage.setFrom(callbackQuery.getFrom());
                        // 直接触发命令
                        handleCommand(newMessage);
                    }
                    case "cmd_info" -> {
                        // 创建虚拟消息来触发命令处理
                        Message message = callbackQuery.getMessage();
                        Message newMessage = new Message();
                        newMessage.setChat(message.getChat());
                        newMessage.setText("/info");
                        newMessage.setFrom(callbackQuery.getFrom());
                        // 直接触发命令
                        handleCommand(newMessage);
                    }
                    default -> {
                        log.warn("Unknown callback data: {}", data);
                        sendMarkdownMessage(chatId, "❌ 未知操作，请重试。");
                    }
                }
            }

        } catch (NumberFormatException e) {
            log.error("Invalid number format in callback: {}", data, e);
            try {
                sendMarkdownMessage(chatId, "❌ 数据格式错误，请重试。");
            } catch (Exception ex) {
                log.error("Failed to send error message", ex);
            }
        } catch (Exception e) {
            log.error("Unexpected error in handleCallbackQuery: {}", data, e);
            try {
                sendMarkdownMessage(chatId, "❌ 处理请求时发生错误，请稍后重试。");
            } catch (Exception ex) {
                log.error("Failed to send error message", ex);
            }
        }
    }

    private void sendTemplateListMenu(Long chatId) {
        List<LotteryTemplate> templates = templateService.getAllTemplates();

        if (templates.isEmpty()) {
            sendMarkdownMessage(chatId, "目前没有任何抽奖模板。\n\n使用 /tpl 创建新模板。");
            sendTemplateMenu(chatId);
            return;
        }

        String menuText = """
                *📋 模板列表*

                请选择要查看的模板：
                """;

        // 每行显示一个模板按钮
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        for (int i = 0; i < templates.size(); i++) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            LotteryTemplate template = templates.get(i);
            row.add(InlineKeyboardButton.builder()
                    .text(template.getId() + ". " + template.getTitle())
                    .callbackData("tpl_detail:" + template.getId())
                    .build());
            keyboard.add(row);
        }

        // 添加返回按钮
        keyboard.add(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ 返回模板菜单")
                        .callbackData("menu_template")
                        .build()
        ));

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(keyboard)
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(menuText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(markup)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("发送模板列表失败", e);
        }
    }

    private void sendStartLotteryTemplateMenu(Long chatId) {
        List<LotteryTemplate> templates = templateService.getAllTemplates();

        if (templates.isEmpty()) {
            sendMarkdownMessage(chatId, "目前没有任何抽奖模板。\n\n使用 /tpl 创建新模板。");
            sendLotteryMenu(chatId);
            return;
        }

        String menuText = """
                *🎁 开启抽奖 选择模板*
                
                请选择一个模板来创建抽奖活动：
                """;

        // 每行显示一个模板按钮
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        for (int i = 0; i < templates.size(); i++) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            LotteryTemplate template = templates.get(i);
            row.add(InlineKeyboardButton.builder()
                    .text("🎯 " + template.getTitle())
                    .callbackData("start_lottery:" + template.getId())
                    .build());
            keyboard.add(row);
        }

        // 添加返回和刷新按钮
        keyboard.add(List.of(
                InlineKeyboardButton.builder()
                        .text("🆕 创建模板")
                        .callbackData("cmd_tpl")
                        .build(),
                InlineKeyboardButton.builder()
                        .text("🔄 刷新列表")
                        .callbackData("cmd_startlottery")
                        .build()
        ));
        keyboard.add(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ 返回抽奖菜单")
                        .callbackData("menu_lottery")
                        .build()
        ));

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(keyboard)
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(menuText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(markup)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("发送开启抽奖模板菜单失败", e);
        }
    }

    private void handleStartLotteryFromTemplate(CallbackQuery callbackQuery, Long templateId) {
        Long chatId = callbackQuery.getMessage().getChatId();
        Long userId = callbackQuery.getFrom().getId();
        boolean isAdmin = userId.equals(botConfig.getAdminId());

        Optional<LotteryTemplate> optTemplate = templateService.getTemplateById(templateId);
        if (optTemplate.isEmpty()) {
            sendMarkdownMessage(chatId, "未找到该模板。");
            sendStartLotteryTemplateMenu(chatId);
            return;
        }

        LotteryTemplate template = optTemplate.get();

        // 显示模板详情并请求确认
        String confirmText = String.format("""
                *📄 确认开启抽奖*

                *模板标题:* %s
                *奖品:* %s
                *获奖名额:* %d人
                """,
                template.getTitle(),
                template.getPrize(),
                template.getWinnerCount()
        );

        if (template.getDefaultEndHours() != null) {
            confirmText += String.format("\n*默认时长:* %d 小时\n", template.getDefaultEndHours());
        } else {
            confirmText += "\n*默认时长:* 24 小时\n";
        }

        if (template.getDescription() != null && !template.getDescription().isEmpty()) {
            confirmText += String.format("*说明:* %s\n", template.getDescription());
        }

        confirmText += "\n*是否确认使用此模板开启抽奖？*";

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("✅ 确认开启")
                                .callbackData("confirm_start_lottery:" + templateId)
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("❌ 取消")
                                .callbackData("menu_lottery")
                                .build()
                ))
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(confirmText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(keyboard)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("发送确认消息失败", e);
        }
    }

    private void handleConfirmStartLottery(CallbackQuery callbackQuery, Long templateId) {
        Long chatId = callbackQuery.getMessage().getChatId();
        Long userId = callbackQuery.getFrom().getId();
        boolean isAdmin = userId.equals(botConfig.getAdminId());

        Optional<LotteryTemplate> optTemplate = templateService.getTemplateById(templateId);
        if (optTemplate.isEmpty()) {
            sendMarkdownMessage(chatId, "未找到该模板。");
            sendStartLotteryTemplateMenu(chatId);
            return;
        }

        LotteryTemplate template = optTemplate.get();

        // 使用默认时长创建抽奖
        int endHours = template.getDefaultEndHours() != null ? template.getDefaultEndHours() : 24;
        LocalDateTime endTime = LocalDateTime.now().plusHours(endHours);

        Lottery lottery = lotteryService.createLottery(
                chatId,
                userId,
                getFullName(callbackQuery.getFrom()),
                template.getTitle(),
                template.getPrize(),
                template.getWinnerCount(),
                template.getDescription(),
                endTime
        );

        // 自动激活并推送到所有抽奖群
        activateLotteryInAllGroups(lottery);

        String response = String.format("""
                *✅ 抽奖已开始！*

                *抽奖 ID:* %d
                *标题:* %s
                *奖品:* %s
                *获奖名额:* %d人
                *截止时间:* %s

                ✅ 已自动推送到所有抽奖群！
                用户可以在抽奖群点击按钮参与抽奖。
                使用 /draw %d 进行开奖
                """,
                lottery.getId(),
                lottery.getTitle(),
                lottery.getPrize(),
                lottery.getWinnerCount(),
                lottery.getEndTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                lottery.getId()
        );

        sendMarkdownMessage(chatId, response);
    }

    private void sendTemplateDetailMenu(Long chatId, Long templateId) {
        Optional<LotteryTemplate> optTemplate = templateService.getTemplateById(templateId);
        if (optTemplate.isEmpty()) {
            sendMarkdownMessage(chatId, "未找到该模板。");
            sendTemplateListMenu(chatId);
            return;
        }

        LotteryTemplate template = optTemplate.get();

        String detailText = String.format("""
                *📄 模板详情*

                *ID:* %d
                *标题:* %s
                *奖品:* %s
                *人数:* %d人
                *说明:* %s
                *默认时长:* %s
                *创建者:* %s
                """,
                template.getId(),
                template.getTitle(),
                template.getPrize() != null ? template.getPrize() : "未设置",
                template.getWinnerCount(),
                template.getDescription() != null ? template.getDescription() : "无",
                template.getDefaultEndHours() != null ? template.getDefaultEndHours() + "小时" : "无",
                template.getCreatorName()
        );

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(
                        List.of(
                                InlineKeyboardButton.builder()
                                        .text("✏️ 修改模板")
                                        .callbackData("tpl_edit:" + templateId)
                                        .build(),
                                InlineKeyboardButton.builder()
                                        .text("🗑️ 删除模板")
                                        .callbackData("tpl_del:" + templateId)
                                        .build()
                        )
                )
                .keyboardRow(
                        List.of(
                                InlineKeyboardButton.builder()
                                        .text("🚀 使用此模板开启抽奖")
                                        .callbackData("start_lottery:" + templateId)
                                        .build()
                        )
                )
                .keyboardRow(
                        List.of(
                                InlineKeyboardButton.builder()
                                        .text("⬅️ 返回模板管理")
                                        .callbackData("menu_template")
                                        .build()
                        )
                )
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(detailText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(keyboard)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("发送模板详情失败", e);
        }
    }

    // ==================== 创建抽奖（状态机） ====================

    private void startCreateLottery(Message message) {
        Long userId = message.getFrom().getId();
        Long chatId = message.getChatId();

        // 初始化会话
        CreateLotterySession session = new CreateLotterySession();
        session.chatId = chatId;
        session.creatorId = userId;
        session.creatorName = getFullName(message.getFrom());
        session.step = CreateStep.TITLE;
        createSessions.put(userId, session);

        sendMarkdownMessage(chatId,
                "*[创建抽奖]* 第 1/5 步\n\n请输入抽奖 *标题*：\n\n_例如：双十一大抽奖_");
    }

    private void handleTextInput(Message message) {
        Long userId = message.getFrom().getId();
        CreateLotterySession session = createSessions.get(userId);
        EditTemplateSession editSession = editTemplateSessions.get(userId);

        if (session != null) {
            handleCreateLotteryInput(message, session, userId);
        } else if (editSession != null) {
            handleEditTemplateInput(message, editSession, userId);
        }
    }

    private void handleCreateLotteryInput(Message message, CreateLotterySession session, Long userId) {
        String input = message.getText().trim();

        switch (session.step) {
            case TITLE -> {
                session.title = input;
                session.step = CreateStep.PRIZE;
                sendMarkdownMessage(message.getChatId(),
                        "*[创建抽奖]* 第 2/5 步\n\n请输入 *奖品描述*：\n\n_例如：iPhone 16 Pro x1_");
            }
            case PRIZE -> {
                session.prize = input;
                session.step = CreateStep.WINNER_COUNT;
                sendMarkdownMessage(message.getChatId(),
                        "*[创建抽奖]* 第 3/5 步\n\n请输入 *获奖名额数量*（数字）：\n\n_例如：3_");
            }
            case WINNER_COUNT -> {
                try {
                    int count = Integer.parseInt(input);
                    if (count < 1 || count > 100) {
                        throw new NumberFormatException();
                    }
                    session.winnerCount = count;
                    session.step = CreateStep.DESCRIPTION;
                    sendMarkdownMessage(message.getChatId(),
                            "*[创建抽奖]* 第 4/5 步\n\n请输入 *抽奖说明*（可选，输入 \"无\" 跳过）：");
                } catch (NumberFormatException e) {
                    sendMarkdownMessage(message.getChatId(),
                            "请输入 1-100 之间的有效数字，重新输入获奖名额：");
                }
            }
            case DESCRIPTION -> {
                session.description = input.equals("无") ? "" : input;
                session.step = CreateStep.END_TIME;
                sendMarkdownMessage(message.getChatId(),
                        """
                        *[创建抽奖]* 第 5/5 步

                        请输入 *截止时间*（可选）：
                        - 格式：`yyyy-MM-dd HH:mm`（例如：2026-12-31 20:00）
                        - 输入 `无` 表示不限时间，手动开奖
                        """);
            }
            case END_TIME -> {
                LocalDateTime endTime = null;
                if (!input.equals("无")) {
                    try {
                        endTime = LocalDateTime.parse(input, FORMATTER);
                        if (endTime.isBefore(LocalDateTime.now())) {
                            sendMarkdownMessage(message.getChatId(),
                                    "截止时间不能早于当前时间，请重新输入：");
                            return;
                        }
                    } catch (Exception e) {
                        sendMarkdownMessage(message.getChatId(),
                                "时间格式错误，请按 `yyyy-MM-dd HH:mm` 格式输入，或输入 `无`：");
                        return;
                    }
                }
                session.endTime = endTime;
                createSessions.remove(userId);
                finishCreateLottery(session);
            }
        }
    }

    private void handleEditTemplateInput(Message message, EditTemplateSession session, Long userId) {
        String input = message.getText().trim();
        Long chatId = message.getChatId();

        switch (session.step) {
            case TITLE -> {
                if (!input.equals("无")) {
                    session.title = input;
                }
                session.step = EditTemplateStep.PRIZE;
                sendMarkdownMessage(chatId,
                        "*[编辑模板]* \n\n请输入奖品描述（直接发送保持不变，输入 \"无\" 跳过）：");
            }
            case PRIZE -> {
                if (!input.equals("无")) {
                    session.prize = input;
                }
                session.step = EditTemplateStep.WINNER_COUNT;
                sendMarkdownMessage(chatId,
                        "*[编辑模板]* \n\n请输入获奖名额数量（直接发送保持不变）：");
            }
            case WINNER_COUNT -> {
                if (!input.equals("无")) {
                    try {
                        int count = Integer.parseInt(input);
                        if (count >= 1 && count <= 100) {
                            session.winnerCount = count;
                        }
                    } catch (NumberFormatException e) {
                        sendMarkdownMessage(chatId, "格式错误，保持原值。");
                    }
                }
                session.step = EditTemplateStep.DESCRIPTION;
                sendMarkdownMessage(chatId,
                        "*[编辑模板]* \n\n请输入抽奖说明（直接发送保持不变，输入 \"无\" 跳过）：");
            }
            case DESCRIPTION -> {
                if (!input.equals("无")) {
                    session.description = input;
                }
                session.step = EditTemplateStep.END_HOURS;
                sendMarkdownMessage(chatId,
                        "*[编辑模板]* \n\n请输入默认截止时长（小时，直接发送保持不变，输入 \"无\" 跳过）：");
            }
            case END_HOURS -> {
                if (!input.equals("无")) {
                    try {
                        int hours = Integer.parseInt(input);
                        if (hours > 0) {
                            session.defaultEndHours = hours;
                        }
                    } catch (NumberFormatException e) {
                        sendMarkdownMessage(chatId, "格式错误，保持原值。");
                    }
                }
                editTemplateSessions.remove(userId);

                if (session.templateId==null){
                    templateService.createTemplate(
                            session.userId,
                            session.userId.toString(),
                            session.title,
                            session.description,
                            session.prize,
                            session.winnerCount,
                            session.defaultEndHours
                    );
                }else{
                    templateService.updateTemplate(
                            session.templateId,
                            session.userId,
                            session.isAdmin,
                            session.title,
                            session.description,
                            session.prize,
                            session.winnerCount,
                            session.defaultEndHours
                    );

                }

                sendMarkdownMessage(chatId, "*模板已更新！*");
            }
        }
    }

    private void finishCreateLottery(CreateLotterySession session) {
        Lottery lottery = lotteryService.createLottery(
                session.chatId,
                session.creatorId,
                session.creatorName,
                session.title,
                session.prize,
                session.winnerCount,
                session.description,
                session.endTime
        );

        String adminLanguage = botConfig.getGroupLanguage(session.chatId);
        String announcement = buildLotteryAnnouncement(lottery, 0, adminLanguage, session.chatId);
        InlineKeyboardMarkup keyboard = buildJoinKeyboard(lottery.getId(), adminLanguage);

        // 在管理群发送公告
        sendLotteryToGroup(session.chatId, announcement, keyboard, adminLanguage);

        // 在所有抽奖群发送公告并置顶
        for (Long groupId : botConfig.getLotteryGroupIds()) {
            String groupLanguage = botConfig.getGroupLanguage(groupId);
            String groupAnnouncement = buildLotteryAnnouncement(lottery, 0, groupLanguage, groupId);
            InlineKeyboardMarkup groupKeyboard = buildJoinKeyboard(lottery.getId(), groupLanguage);
            sendLotteryToGroup(groupId, groupAnnouncement, groupKeyboard, groupLanguage);
        }
    }

    private void sendLotteryToGroup(Long chatId, String announcement, InlineKeyboardMarkup keyboard, String language) {
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(announcement))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(keyboard)
                .build();

        try {
            Message sent = execute(msg);
            log.info("Lottery announcement sent to chat {}, messageId={}", chatId, sent.getMessageId());

            if (botConfig.getLotteryGroupIds().contains(chatId)) {
                pinLotteryMessage(chatId, sent.getMessageId());
            }
        } catch (TelegramApiException e) {
            log.error("Failed to send lottery announcement to chat {}: {}", chatId, e.getMessage());
        }
    }

    // ==================== 参与抽奖 ====================

    private void handleJoinCommand(Message message) {
        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();

        Optional<Lottery> activeLottery = lotteryService.getActiveLottery(chatId);
        if (activeLottery.isEmpty()) {
            sendMarkdownMessage(chatId, "当前没有进行中的抽奖活动，请等待管理员创建。");
            return;
        }

        String language = botConfig.getGroupLanguage(chatId);
        processJoin(chatId, activeLottery.get().getId(), message.getFrom(), message.getMessageId(), language);
    }

    private void processJoin(Long chatId, Long lotteryId, User user, Integer replyToMessageId, String language) {
        try {
            LotteryService.JoinResult result = lotteryService.joinLottery(
                    lotteryId,
                    user.getId(),
                    user.getUserName(),
                    getFullName(user)
            );

            String userMention = getUserMention(user);
            String response;
            String key = switch (result) {
                case SUCCESS -> "join_success";
                case ALREADY_JOINED -> "join_already";
                case LOTTERY_ENDED -> "join_ended";
                case LOTTERY_EXPIRED -> "join_expired";
                case NOT_FOUND -> "join_not_found";
            };
            if (result == LotteryService.JoinResult.SUCCESS) {
                response = maskUsername(userMention )+ " " + localizationService.get(key, language, lotteryService.getParticipantCount(lotteryId));
            } else {
                response = maskUsername(userMention )+ " " + localizationService.get(key, language);
            }

            if (replyToMessageId != null) {
                sendReplyMessage(chatId, response, replyToMessageId);
            } else {
                sendMarkdownMessage(chatId, response);
            }
            log.info("User [{}] joined lottery [{}] with result: {}", user.getId(), lotteryId, result);
        } catch (Exception e) {
            log.error("Error processing join request for lottery [{}] by user [{}]", lotteryId, user.getId(), e);
            try {
                sendMarkdownMessage(chatId, "❌ " + localizationService.get("join_error", language));
            } catch (Exception ex) {
                log.error("Failed to send error message to chat [{}]", chatId, ex);
            }
        }
    }

    private String getUserMention(User user) {
        if (user.getUserName() != null && !user.getUserName().isEmpty()) {
            return "@" + user.getUserName();
        }
        return "*" + getFullName(user) + "*";
    }

    private void sendReplyMessage(Long chatId, String text, Integer replyToMessageId) {
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(text))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyToMessageId(replyToMessageId)
                .build();
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send reply message to chat {}: {}", chatId, e.getMessage());
        }
    }

    // ==================== 开奖 ====================

    private void handleDrawCommand(Message message) {
        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();
        boolean isAdmin = userId.equals(botConfig.getAdminId());

        // 解析命令参数（/draw 或 /draw 123）
        Long lotteryId = null;
        String[] parts = message.getText().split(" ");
        if (parts.length > 1) {
            try {
                lotteryId = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                sendMarkdownMessage(chatId, "抽奖 ID 格式错误，请输入数字。");
                return;
            }
        }

        if (lotteryId == null) {
            Optional<Lottery> active = lotteryService.getActiveLottery(chatId);
            if (active.isEmpty()) {
                sendMarkdownMessage(chatId, "当前没有进行中的抽奖活动。");
                return;
            }
            lotteryId = active.get().getId();
        }

        // 权限检查
        Lottery lottery = lotteryService.getLotteryById(lotteryId).orElse(null);
        if (lottery == null) {
            sendMarkdownMessage(chatId, "未找到该抽奖活动。");
            return;
        }
        if (!isAdmin && !lottery.getCreatorId().equals(userId) && !isAdminGroup(chatId)) {
            sendMarkdownMessage(chatId, "只有抽奖创建者、管理员或管理群成员才能执行开奖操作。");
            return;
        }

        executeDraw(chatId, lotteryId);
    }

    private void executeDraw(Long chatId, Long lotteryId) {
        String language = botConfig.getGroupLanguage(chatId);
        sendMarkdownMessage(chatId, localizationService.get("draw_in_progress", language));

        LotteryService.DrawResult result = lotteryService.drawLottery(lotteryId, null);

        String response = switch (result.status) {
            case SUCCESS -> buildDrawResultMessage(result.lottery, result.winners, language, chatId);
            case NO_PARTICIPANTS -> localizationService.get("no_participants_draw", language);
            case NO_ELIGIBLE_PARTICIPANTS -> localizationService.get("no_eligible_participants", language);
            case ALREADY_DRAWN -> localizationService.get("already_drawn", language);
            case NOT_FOUND -> localizationService.get("lottery_not_found", language);
        };

        sendHtmlMessage(chatId, response);

        unpinAllLotteryGroups();

        for (Long groupId : botConfig.getLotteryGroupIds()) {
            String groupLanguage = botConfig.getGroupLanguage(groupId);
            String groupResponse = switch (result.status) {
                case SUCCESS -> buildDrawResultMessage(result.lottery, result.winners, groupLanguage, groupId);
                case NO_PARTICIPANTS -> localizationService.get("no_participants_draw", groupLanguage);
                case NO_ELIGIBLE_PARTICIPANTS -> localizationService.get("no_eligible_participants", groupLanguage);
                case ALREADY_DRAWN -> localizationService.get("already_drawn", groupLanguage);
                case NOT_FOUND -> localizationService.get("lottery_not_found", groupLanguage);
            };
            sendHtmlMessage(groupId, groupResponse);
        }
    }

    private void unpinAllLotteryGroups() {
        try {
            for (Long groupId : botConfig.getLotteryGroupIds()) {
                unpinMessage(groupId);
            }
            log.info("Unpinned messages in all lottery groups");
        } catch (Exception e) {
            log.error("Failed to unpin messages: {}", e.getMessage());
        }
    }

    private void unpinMessage(Long chatId) {
        try {
            org.telegram.telegrambots.meta.api.methods.pinnedmessages.UnpinChatMessage unpin =
                org.telegram.telegrambots.meta.api.methods.pinnedmessages.UnpinChatMessage.builder()
                    .chatId(chatId.toString())
                    .build();
            execute(unpin);
        } catch (TelegramApiException e) {
            log.debug("No pinned message to unpin in chat {}: {}", chatId, e.getMessage());
        }
    }

    private String buildDrawResultMessage(Lottery lottery, List<Participant> winners, String language, Long groupId) {
        StringBuilder sb = new StringBuilder();
        sb.append(localizationService.get("draw_result_title", language)).append("\n\n");
        sb.append(localizationService.get("lottery_name", language)).append(lottery.getTitle()).append("\n");
        sb.append(localizationService.get("draw_prize", language)).append(lottery.getPrize()).append("\n");
        sb.append(localizationService.get("draw_time", language)).append(
                lottery.getDrawnAt().atZone(java.time.ZoneId.systemDefault())
                        .withZoneSameInstant(botConfig.getGroupZoneId(groupId))
                        .format(FORMATTER)
        ).append("\n\n");

        if (winners.isEmpty()) {
            sb.append(localizationService.get("no_winners_text", language));
        } else {
            sb.append(localizationService.get("winners_label", language)).append("\n");
            for (int i = 0; i < winners.size(); i++) {
                Participant w = winners.get(i);
                sb.append(i + 1).append(". ");
                String displayName = w.getUsername() != null && !w.getUsername().isEmpty() ? w.getUsername() : w.getFullName();
                sb.append("<a href=\"tg://user?id=").append(w.getUserId()).append("\">").append(displayName).append("</a>");
                sb.append("\n");
            }
        }

        sb.append("\n").append(localizationService.get("congratulations", language));
        return sb.toString();
    }

    // ==================== 取消抽奖 ====================

    private void handleCancelCommand(Message message) {
        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();
        boolean isAdmin = userId.equals(botConfig.getAdminId()) || isAdminGroup(chatId);

        Long lotteryId = null;
        String[] parts = message.getText().split(" ");
        if (parts.length > 1) {
            try {
                lotteryId = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                sendMarkdownMessage(chatId, "抽奖 ID 格式错误。");
                return;
            }
        } else {
            Optional<Lottery> active = lotteryService.getActiveLottery(chatId);
            if (active.isEmpty()) {
                sendMarkdownMessage(chatId, "当前没有进行中的抽奖。");
                return;
            }
            lotteryId = active.get().getId();
        }

        boolean cancelled = lotteryService.cancelLottery(lotteryId, userId, isAdmin);
        if (cancelled) {
            sendMarkdownMessage(chatId, "*抽奖已成功取消。*");
            unpinAllLotteryGroups();
        } else {
            sendMarkdownMessage(chatId, "取消失败：你可能没有权限或抽奖已结束。");
        }
    }

    // ==================== 抽奖列表管理 ====================

    private void handleLotteryListCommand(Message message) {
        Long chatId = message.getChatId();
        List<Lottery> lotteries = lotteryService.getAllLotteries().stream()
                .sorted(Comparator.comparing(Lottery::getCreatedAt).reversed())
                .collect(Collectors.toList());

        String menuText = "*📋 抽奖列表*\n\n选择抽奖进行操作：";

        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        for (Lottery l : lotteries) {
            String status = switch (l.getStatus()) {
                case ACTIVE -> "✅";
                case DRAWN -> "🏁";
                case CANCELLED -> "❌";
            };
            long participantCount = lotteryService.getParticipantCount(l.getId());
            String createdTime = l.getCreatedAt() != null ? l.getCreatedAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) : "";
            String buttonText = String.format("%s ID:%d %s (%d人|%s)",
                    status, l.getId(), truncate(l.getTitle(), 10), participantCount, createdTime);
            keyboard.add(List.of(
                    InlineKeyboardButton.builder()
                            .text(buttonText)
                            .callbackData("lottery_detail:" + l.getId())
                            .build()
            ));
        }

        keyboard.add(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ 返回")
                        .callbackData("menu_lottery")
                        .build()
        ));

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(keyboard)
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(menuText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(markup)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("发送抽奖列表失败", e);
        }
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() <= maxLength ? str : str.substring(0, maxLength) + "...";
    }

    private void sendLotteryDetailMenu(Long chatId, Long lotteryId) {
        Optional<Lottery> optLottery = lotteryService.getLotteryById(lotteryId);
        if (optLottery.isEmpty()) {
            sendMarkdownMessage(chatId, "未找到该抽奖活动。");
            return;
        }

        Lottery lottery = optLottery.get();
        long participantCount = lotteryService.getParticipantCount(lotteryId);

        String status = switch (lottery.getStatus()) {
            case ACTIVE -> "✅ 进行中";
            case DRAWN -> "🏁 已结束";
            case CANCELLED -> "❌ 已取消";
        };

        String detailText = String.format("""
                <b>🎁 抽奖详情</b>

                <b>ID:</b> %d
                <b>标题:</b> %s
                <b>奖品:</b> %s
                <b>状态:</b> %s
                <b>获奖名额:</b> %d人
                <b>参与人数:</b> %d人
                <b>开启时间:</b> %s
                <b>截止时间:</b> %s
                """,
                lottery.getId(),
                lottery.getTitle(),
                lottery.getPrize(),
                status,
                lottery.getWinnerCount(),
                participantCount,
                lottery.getCreatedAt() != null ? lottery.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "未知",
                lottery.getEndTime() != null ? lottery.getEndTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "未知"
        );

        if (lottery.getStatus() == Lottery.LotteryStatus.DRAWN) {
            List<Participant> participants = lotteryService.getParticipants(lotteryId);
            List<Participant> winners = participants.stream()
                    .filter(Participant::isWinner)
                    .collect(Collectors.toList());

            if (!winners.isEmpty()) {
                detailText += "\n<b>🏆 中奖人:</b>\n";
                for (Participant winner : winners) {
                    String displayName = winner.getUsername() != null && !winner.getUsername().isEmpty() ? winner.getUsername() : winner.getFullName();
                    detailText += "• <a href=\"tg://user?id=" + winner.getUserId() + "\">" + displayName + "</a>\n";
                }
            }

             String prizeStatus = lottery.isPrizeDistributed() ? "✅ 已发放" : "❌ 未发放";
             detailText += "\n<b>📦 奖项发放:</b> " + prizeStatus;
        }

        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        if (lottery.getStatus() == Lottery.LotteryStatus.ACTIVE) {
            keyboard.add(List.of(
                    InlineKeyboardButton.builder()
                            .text("📡 置顶抽奖")
                            .callbackData("lottery_activate:" + lottery.getId())
                            .build(),
                    InlineKeyboardButton.builder()
                            .text("⏹️ 取消置顶")
                            .callbackData("lottery_deactivate:" + lottery.getId())
                            .build()
            ));
            keyboard.add(List.of(
                    InlineKeyboardButton.builder()
                            .text("🎲 开奖")
                            .callbackData("lottery_draw:" + lottery.getId())
                            .build(),
                    InlineKeyboardButton.builder()
                            .text("🗑️ 删除抽奖")
                            .callbackData("lottery_delete:" + lottery.getId())
                            .build()
            ));
        } else if (lottery.getStatus() == Lottery.LotteryStatus.DRAWN) {
            if (!lottery.isPrizeDistributed()) {
                keyboard.add(List.of(
                        InlineKeyboardButton.builder()
                                .text("✅ 确认已发放")
                                .callbackData("lottery_prize_distributed:" + lottery.getId())
                                .build()
                ));
            }
            keyboard.add(List.of(
                    InlineKeyboardButton.builder()
                            .text("🗑️ 删除抽奖")
                            .callbackData("lottery_delete:" + lottery.getId())
                            .build()
            ));
        } else if (lottery.getStatus() == Lottery.LotteryStatus.CANCELLED) {
            keyboard.add(List.of(
                    InlineKeyboardButton.builder()
                            .text("🗑️ 删除抽奖")
                            .callbackData("lottery_delete:" + lottery.getId())
                            .build()
            ));
        }

        keyboard.add(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ 返回列表")
                        .callbackData("cmd_lotterylist")
                        .build()
        ));

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(keyboard)
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(detailText)
                .parseMode(ParseMode.HTML)
                .replyMarkup(markup)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error(e.getMessage());
            log.error("发送抽奖详情失败", e);
        }
    }

    private void handleLotteryActivate(CallbackQuery callbackQuery, Long lotteryId) {
        Long chatId = callbackQuery.getMessage().getChatId();
        Optional<Lottery> optLottery = lotteryService.getLotteryById(lotteryId);
        if (optLottery.isEmpty()) {
            sendMarkdownMessage(chatId, "未找到该抽奖活动。");
            return;
        }
        Lottery lottery = optLottery.get();
        activateLotteryInAllGroups(lottery);
        sendMarkdownMessage(chatId, "*抽奖已激活！*\n\n已推送到所有抽奖群并置顶。");
        sendLotteryDetailMenu(chatId, lotteryId);
    }

    private void handleLotteryDeactivate(CallbackQuery callbackQuery, Long lotteryId) {
        Long chatId = callbackQuery.getMessage().getChatId();
        unpinAllLotteryGroups();
        sendMarkdownMessage(chatId, "*已取消所有抽奖群的置顶。*");
        sendLotteryDetailMenu(chatId, lotteryId);
    }

    private void handleLotteryDraw(CallbackQuery callbackQuery, Long lotteryId) {
        Long chatId = callbackQuery.getMessage().getChatId();
        executeDraw(chatId, lotteryId);
    }

    private void handleLotteryDelete(CallbackQuery callbackQuery, Long lotteryId) {
        Long chatId = callbackQuery.getMessage().getChatId();
        lotteryService.deleteLottery(lotteryId);
        sendMarkdownMessage(chatId, "*抽奖已删除。*");
        handleLotteryListCommand(callbackQuery.getMessage());
    }

    private void handleLotteryPrizeDistributed(CallbackQuery callbackQuery, Long lotteryId) {
        Long chatId = callbackQuery.getMessage().getChatId();
        lotteryService.setPrizeDistributed(lotteryId, true);
        sendMarkdownMessage(chatId, "*奖项已标记为已发放。*");
        sendLotteryDetailMenu(chatId, lotteryId);
    }

    private void handleActivateCommand(Message message) {
        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();
        boolean isAdmin = userId.equals(botConfig.getAdminId());

        Long lotteryId = null;
        String[] parts = message.getText().split(" ");
        if (parts.length > 1) {
            try {
                lotteryId = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                sendMarkdownMessage(chatId, "抽奖 ID 格式错误。");
                return;
            }
        }

        if (lotteryId == null) {
            sendMarkdownMessage(chatId, "请指定要激活的抽奖 ID，如：/activate 1");
            return;
        }

        Optional<Lottery> optLottery = lotteryService.getLotteryById(lotteryId);
        if (optLottery.isEmpty()) {
            sendMarkdownMessage(chatId, "未找到该抽奖活动。");
            return;
        }

        Lottery lottery = optLottery.get();
        if (lottery.getStatus() != Lottery.LotteryStatus.ACTIVE) {
            sendMarkdownMessage(chatId, "只有进行中的抽奖才能被激活。");
            return;
        }

        if (!isAdmin && !lottery.getCreatorId().equals(userId) && !isAdminGroup(chatId)) {
            sendMarkdownMessage(chatId, "只有抽奖创建者、管理员或管理群成员才能激活抽奖。");
            return;
        }

        activateLotteryInAllGroups(lottery);
        sendMarkdownMessage(chatId, "*抽奖已激活！*\n\n已推送到所有抽奖群并置顶。");
    }

    private void activateLotteryInAllGroups(Lottery lottery) {
        for (Long groupId : botConfig.getLotteryGroupIds()) {
            String language = botConfig.getGroupLanguage(groupId);
            String announcement = buildLotteryAnnouncement(lottery, lotteryService.getParticipantCount(lottery.getId()), language, groupId);
            InlineKeyboardMarkup keyboard = buildJoinKeyboard(lottery.getId(), language);
            sendLotteryToGroup(groupId, announcement, keyboard, language);
        }
    }

    private void handleDeactivateCommand(Message message) {
        Long chatId = message.getChatId();
        unpinAllLotteryGroups();
        sendMarkdownMessage(chatId, "*已取消所有抽奖群的置顶。*");
    }

    private void handleDeleteCommand(Message message) {
        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();
        boolean isAdmin = userId.equals(botConfig.getAdminId());

        Long lotteryId = null;
        String[] parts = message.getText().split(" ");
        if (parts.length > 1) {
            try {
                lotteryId = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                sendMarkdownMessage(chatId, "抽奖 ID 格式错误。");
                return;
            }
        }

        if (lotteryId == null) {
            sendMarkdownMessage(chatId, "请指定要删除的抽奖 ID，如：/delete 1");
            return;
        }

        Optional<Lottery> optLottery = lotteryService.getLotteryById(lotteryId);
        if (optLottery.isEmpty()) {
            sendMarkdownMessage(chatId, "未找到该抽奖活动。");
            return;
        }

        Lottery lottery = optLottery.get();
        if (!isAdmin && !lottery.getCreatorId().equals(userId) && !isAdminGroup(chatId)) {
            sendMarkdownMessage(chatId, "只有抽奖创建者、管理员或管理群成员才能删除抽奖。");
            return;
        }

        lotteryService.deleteLottery(lotteryId);
        sendMarkdownMessage(chatId, "*抽奖已删除。*");
    }

    // ==================== 模板管理 ====================

    private void startCreateTemplate(Message message) {
        Long userId = message.getFrom().getId();
        Long chatId = message.getChatId();

        EditTemplateSession session = new EditTemplateSession();
        session.userId = userId;
//        session.chatId = chatId;
        session.isAdmin = userId.equals(botConfig.getAdminId());
        session.step = EditTemplateStep.TITLE;
        editTemplateSessions.put(userId, session);

        sendMarkdownMessage(chatId,
                "*[创建抽奖模板]* 第 1/5 步\n\n请输入模板 *标题*：\n\n_例如：每日福利抽奖_");
    }

    private void startEditTemplate(Message message) {
        Long userId = message.getFrom().getId();
        Long chatId = message.getChatId();

        String[] parts = message.getText().split(" ");
        if (parts.length < 2) {
            sendMarkdownMessage(chatId, "请指定要编辑的模板 ID，如：/tpledit 1");
            return;
        }

        try {
            Long templateId = Long.parseLong(parts[1]);
            Optional<LotteryTemplate> optTemplate = templateService.getTemplateById(templateId);
            if (optTemplate.isEmpty()) {
                sendMarkdownMessage(chatId, "未找到该模板。");
                return;
            }

            LotteryTemplate template = optTemplate.get();
            boolean isAdmin = userId.equals(botConfig.getAdminId());
            if (!isAdmin && !template.getCreatorId().equals(userId)) {
                sendMarkdownMessage(chatId, "只有模板创建者或管理员才能编辑模板。");
                return;
            }

            EditTemplateSession session = new EditTemplateSession();
            session.templateId = templateId;
            session.userId = userId;
            session.isAdmin = isAdmin;
            session.title = template.getTitle();
            session.prize = template.getPrize();
            session.winnerCount = template.getWinnerCount();
            session.description = template.getDescription();
            session.defaultEndHours = template.getDefaultEndHours();
            session.step = EditTemplateStep.TITLE;
            editTemplateSessions.put(userId, session);

            sendMarkdownMessage(chatId,
                    "*[编辑抽奖模板 ID:" + templateId + "]*\n\n当前标题: " + template.getTitle() + "\n\n请输入新标题（直接发送保持不变）：");
        } catch (NumberFormatException e) {
            sendMarkdownMessage(chatId, "模板 ID 格式错误。");
        }
    }

    private void handleTemplateListCommand(Message message) {
        Long chatId = message.getChatId();
        List<LotteryTemplate> templates = templateService.getAllTemplates();

        if (templates.isEmpty()) {
            sendMarkdownMessage(chatId, "目前没有任何抽奖模板。\n\n使用 /tpl 创建新模板。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("*== 抽奖模板列表 ==*\n\n");

        for (LotteryTemplate t : templates) {
            sb.append("• *ID:").append(t.getId()).append("*\n");
            sb.append("  标题: ").append(t.getTitle()).append("\n");
            sb.append("  奖品: ").append(t.getPrize() != null ? t.getPrize() : "未设置").append("\n");
            sb.append("  人数: ").append(t.getWinnerCount()).append("人\n");
            if (t.getDefaultEndHours() != null) {
                sb.append("  默认时长: ").append(t.getDefaultEndHours()).append("小时\n");
            }
            sb.append("  创建者: ").append(t.getCreatorName()).append("\n\n");
        }

        sb.append("使用 /startlottery [ID] 开启抽奖");
        sendMarkdownMessage(chatId, sb.toString());
    }

    private void handleDeleteTemplateCommand(Message message) {
        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();
        boolean isAdmin = userId.equals(botConfig.getAdminId());

        Long templateId = null;
        String[] parts = message.getText().split(" ");
        if (parts.length > 1) {
            try {
                templateId = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                sendMarkdownMessage(chatId, "模板 ID 格式错误。");
                return;
            }
        }

        if (templateId == null) {
            sendMarkdownMessage(chatId, "请指定要删除的模板 ID，如：/tpldel 1");
            return;
        }

        boolean deleted = templateService.deleteTemplate(templateId, userId, isAdmin);
        if (deleted) {
            sendMarkdownMessage(chatId, "*模板已删除。*");
        } else {
            sendMarkdownMessage(chatId, "删除失败：你可能没有权限。");
        }
    }

    private void handleStartLotteryCommand(Message message) {
        Long chatId = message.getChatId();
        Long userId = message.getFrom().getId();
        boolean isAdmin = userId.equals(botConfig.getAdminId());

        List<Lottery> activeLotteries = lotteryService.getAllActiveLotteries();
        if (!activeLotteries.isEmpty()) {
            sendMarkdownMessage(chatId, "当前有 " + activeLotteries.size() + " 个正在进行中的抽奖，不允许开启新抽奖。\n\n请先完成或取消现有抽奖。");
            return;
        }

        String[] parts = message.getText().split(" ");
        if (parts.length < 2) {
            sendMarkdownMessage(chatId, "请指定模板 ID，如：/startlottery 1");
            return;
        }

        try {
            Long templateId = Long.parseLong(parts[1]);
            Optional<LotteryTemplate> optTemplate = templateService.getTemplateById(templateId);
            if (optTemplate.isEmpty()) {
                sendMarkdownMessage(chatId, "未找到该模板。");
                return;
            }

            LotteryTemplate template = optTemplate.get();

            int endHours = template.getDefaultEndHours() != null ? template.getDefaultEndHours() : 24;
            LocalDateTime endTime = LocalDateTime.now().plusHours(endHours);

            Lottery lottery = lotteryService.createLottery(
                    chatId,
                    userId,
                    getFullName(message.getFrom()),
                    template.getTitle(),
                    template.getPrize(),
                    template.getWinnerCount(),
                    template.getDescription(),
                    endTime
            );

            sendMarkdownMessage(chatId,
                    "*抽奖已创建！*\n\n" +
                    "抽奖 ID: " + lottery.getId() + "\n" +
                    "标题: " + lottery.getTitle() + "\n" +
                    "奖品: " + lottery.getPrize() + "\n" +
                    "人数: " + lottery.getWinnerCount() + "人\n" +
                    "截止: " + lottery.getEndTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))  + "\n\n" +
                    "使用 /activate " + lottery.getId() + " 激活并推送到抽奖群");
        } catch (NumberFormatException e) {
            sendMarkdownMessage(chatId, "模板 ID 格式错误。");
        }
    }

    private void handleListCommand(Message message) {
        Long chatId = message.getChatId();
        Optional<Lottery> active = lotteryService.getActiveLottery(chatId);

        if (active.isEmpty()) {
            sendMarkdownMessage(chatId, "当前没有进行中的抽奖。");
            return;
        }

        Lottery lottery = active.get();
        List<Participant> participants = lotteryService.getParticipants(lottery.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("*== 参与者列表 ==*\n");
        sb.append("*抽奖：*").append(lottery.getTitle()).append("\n");
        sb.append("*总人数：*").append(participants.size()).append(" 人\n\n");

        if (participants.isEmpty()) {
            sb.append("_暂无人参与_");
        } else {
            for (int i = 0; i < participants.size(); i++) {
                Participant p = participants.get(i);
                sb.append(i + 1).append(". ");
                if (p.getUsername() != null && !p.getUsername().isEmpty()) {
                    sb.append("@" + p.getUsername());
                } else {
                    sb.append(p.getFullName());
                }
                sb.append("\n");
                // 避免消息过长
                if (i >= 49) {
                    sb.append("... 等 ").append(participants.size()).append(" 人");
                    break;
                }
            }
        }

        sendMarkdownMessage(chatId, sb.toString());
    }

    // ==================== 查看抽奖信息 ====================

    private void handleInfoCommand(Message message) {
        Long chatId = message.getChatId();
        Optional<Lottery> active = lotteryService.getActiveLottery(chatId);

        if (active.isEmpty()) {
            sendMarkdownMessage(chatId, "当前没有进行中的抽奖。");
            return;
        }

        Lottery lottery = active.get();
        long count = lotteryService.getParticipantCount(lottery.getId());
        String language = botConfig.getGroupLanguage(chatId);
        String announcement = buildLotteryAnnouncement(lottery, (int) count, language, chatId);

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(announcement))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(buildJoinKeyboard(lottery.getId(), language))
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send info message", e);
        }
    }

    // ==================== Callback 处理 ====================

    private void handleCallback(CallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        User user = callbackQuery.getFrom();
        Long chatId = callbackQuery.getMessage().getChatId();

        if (isPrivateChat(chatId) && !isAdminUser(user.getId())) {
            return;
        }

        try {
            if (data.startsWith("join:")) {
                Long lotteryId = Long.parseLong(data.substring(5));
                String language = botConfig.getGroupLanguage(chatId);
                processJoin(chatId, lotteryId, user, null, language);
            } else {
                handleCallbackQuery(callbackQuery);
            }
        } catch (NumberFormatException e) {
            log.error("Invalid lottery ID in callback: {}", data, e);
            try {
                sendMarkdownMessage(chatId, "❌ 抽奖ID格式错误，请联系管理员。");
            } catch (Exception ex) {
                log.error("Failed to send error message", ex);
            }
        } catch (Exception e) {
            log.error("Error processing callback: {}", data, e);
            try {
                sendMarkdownMessage(chatId, "❌ 处理请求时发生错误，请稍后重试。");
            } catch (Exception ex) {
                log.error("Failed to send error message", ex);
            }
        }

        // 应答 callback，避免按钮转圈
        try {
            execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQuery.getId())
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback", e);
        }
    }

    // ==================== 定时任务：自动开奖 ====================

    @Scheduled(fixedRate = 60000) // 每 60 秒检查一次
    public void autoDrawExpiredLotteries() {
        List<Lottery> expired = lotteryService.getExpiredLotteries();
        for (Lottery lottery : expired) {
            log.info("Auto-drawing expired lottery: {}", lottery.getId());
            sendMarkdownMessage(lottery.getChatId(),
                    "*[系统提示]* 抽奖 \"" + lottery.getTitle() +
                    "\" 已到截止时间，自动开奖中...");
            executeDraw(lottery.getChatId(), lottery.getId());
        }
    }

    // ==================== 工具方法 ====================

    private String buildLotteryAnnouncement(Lottery lottery, long participantCount, String language, Long groupId) {
        String endTimeStr = lottery.getEndTime() != null ? 
                lottery.getEndTime().atZone(java.time.ZoneId.systemDefault())
                        .withZoneSameInstant(botConfig.getGroupZoneId(groupId))
                        .format(FORMATTER) : 
                localizationService.get("manual_draw", language);
        String title = localizationService.get("lottery_announcement", language,
                lottery.getTitle(),
                lottery.getPrize(),
                lottery.getWinnerCount(),
                endTimeStr,lottery.getDescription());

        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n");
        sb.append(localizationService.get("participants_count", language, participantCount)).append("\n");
        sb.append(localizationService.get("lottery_id", language)).append(lottery.getId()).append("`\n\n");
        sb.append("_").append(localizationService.get("join_prompt", language)).append("_");
        return sb.toString();
    }

    private InlineKeyboardMarkup buildJoinKeyboard(Long lotteryId, String language) {
        InlineKeyboardButton joinBtn = InlineKeyboardButton.builder()
                .text(localizationService.get("join_lottery", language))
                .callbackData("join:" + lotteryId)
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(joinBtn))
                .build();
    }

    private void sendMarkdownMessage(Long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(text))
                .parseMode(ParseMode.MARKDOWNV2)
                .build();
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}: {}", chatId, e.getMessage());
        }
    }

    private void pinLotteryMessage(Long chatId, Integer messageId) {
        try {
            PinChatMessage pin = PinChatMessage.builder()
                    .chatId(chatId.toString())
                    .messageId(messageId)
                    .build();
            execute(pin);
            log.info("Pinned lottery message: chatId={}, messageId={}", chatId, messageId);
        } catch (TelegramApiException e) {
            log.error("Failed to pin message: {}", e.getMessage());
        }
    }

    private String getFullName(User user) {
        String name = user.getFirstName();
        if (user.getLastName() != null && !user.getLastName().isEmpty()) {
            name += " " + user.getLastName();
        }
        return name;
    }

    private String maskUsername(String username) {
        if (username == null || username.isEmpty()) {
            return username;
        }
        if (username.length() <= 2) {
            return username;
        }
        String prefix = username.startsWith("@") ? "@" : "";
        String name = username.startsWith("@") ? username.substring(1) : username;
        if (name.length() <= 2) {
            return username;
        }
        return prefix + name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    private String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        String result = text;
        result = result.replace("_", "\\_");
        result = result.replace("*", "\\*");
        result = result.replace("[", "\\[");
        result = result.replace("]", "\\]");
        result = result.replace("(", "\\(");
        result = result.replace(")", "\\)");
        result = result.replace(".", "\\.");
        result = result.replace("`", "\\`");
        result = result.replace("!", "\\!");
        result = result.replace("~", "\\~");
        result = result.replace(">", "\\>");
        result = result.replace("+", "\\+");
        result = result.replace("-", "\\-");
        result = result.replace("=", "\\=");
        result = result.replace("|", "\\|");
        result = result.replace("{", "\\{");
        result = result.replace("}", "\\}");
        return result;
    }

    private void sendHtmlMessage(Long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode(ParseMode.HTML)
                .build();
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}: {}", chatId, e.getMessage());
        }
    }

    // ==================== 会话模型 ====================

    private static class CreateLotterySession {
        Long chatId;
        Long creatorId;
        String creatorName;
        String title;
        String prize;
        int winnerCount;
        String description;
        LocalDateTime endTime;
        CreateStep step;
    }

    private enum CreateStep {
        TITLE, PRIZE, WINNER_COUNT, DESCRIPTION, END_TIME
    }

    private static class EditTemplateSession {
        Long templateId;
        Long userId;
        boolean isAdmin;
        String title;
        String prize;
        int winnerCount;
        String description;
        Integer defaultEndHours;
        EditTemplateStep step;
    }

    private enum EditTemplateStep {
        TEMPLATE_ID, TITLE, PRIZE, WINNER_COUNT, DESCRIPTION, END_HOURS
    }

    private void sendLotteryListForDelete(Long chatId, CallbackQuery callbackQuery) {
        List<Lottery> lotteries = lotteryService.getAllLotteries();

        if (lotteries.isEmpty()) {
            sendMarkdownMessage(chatId, "目前没有任何抽奖活动。");
            sendLotteryMenu(chatId);
            return;
        }

        String menuText = """
                *🗑️ 选择要删除的抽奖*

                请选择要删除的抽奖：
                """;

        // 每行最多显示两个抽奖按钮
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        for (int i = 0; i < lotteries.size(); i += 2) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            Lottery lottery = lotteries.get(i);
            row.add(InlineKeyboardButton.builder()
                    .text(lottery.getId() + ". " + lottery.getTitle())
                    .callbackData("delete_lottery:" + lottery.getId())
                    .build());

            if (i + 1 < lotteries.size()) {
                Lottery nextLottery = lotteries.get(i + 1);
                row.add(InlineKeyboardButton.builder()
                        .text(nextLottery.getId() + ". " + nextLottery.getTitle())
                        .callbackData("delete_lottery:" + nextLottery.getId())
                        .build());
            }
            keyboard.add(row);
        }

        // 添加返回按钮
        keyboard.add(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ 返回抽奖菜单")
                        .callbackData("menu_lottery")
                        .build()
        ));

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboard(keyboard)
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(escapeMarkdown(menuText))
                .parseMode(ParseMode.MARKDOWNV2)
                .replyMarkup(markup)
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("发送抽奖列表失败", e);
        }
    }
}