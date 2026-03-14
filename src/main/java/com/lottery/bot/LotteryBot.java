// bot/LotteryBot.java
package com.lottery.bot;

import com.lottery.bot.config.BotConfig;
import com.lottery.bot.model.Lottery;
import com.lottery.bot.model.Participant;
import com.lottery.bot.service.LotteryService;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.lottery.bot.service.LotteryService.DrawStatus.*;

@Slf4j
@Component
public class LotteryBot extends TelegramLongPollingBot {

    private final BotConfig botConfig;
    private final LotteryService lotteryService;

    // 多步骤创建抽奖状态机（用户ID -> 当前步骤）
    private final Map<Long, CreateLotterySession> createSessions = new ConcurrentHashMap<>();

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public LotteryBot(BotConfig botConfig, LotteryService lotteryService) {
        super(botConfig.getToken());
        this.botConfig = botConfig;
        this.lotteryService = lotteryService;
        log.info("LotteryBot initialized with username: {}", botConfig.getUsername());

    }


    @PostConstruct
    public void init() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("Telegram Bot [{}] started successfully!", getBotUsername());
        } catch (TelegramApiException e) {
            log.error("Failed to start Telegram Bot", e);
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


        switch (command) {
            case "/start", "/help" -> sendHelp(message);
            case "/newlottery"     -> startCreateLottery(message);
            case "/join"           -> handleJoinCommand(message);
            case "/draw"           -> handleDrawCommand(message);
            case "/cancel"         -> handleCancelCommand(message);
            case "/list"           -> handleListCommand(message);
            case "/info"           -> handleInfoCommand(message);
            default                -> { /* 忽略未知命令 */ }
        }
    }

    // ==================== /help ====================

    private void sendHelp(Message message) {
        String helpText = """
                *== Lottery Bot 抽奖机器人 ==*

                *用户命令：*
                /newlottery - 创建新抽奖
                /join - 参与当前抽奖
                /list - 查看参与者列表
                /info - 查看当前抽奖信息

                *管理员/创建者命令：*
                /draw [ID] - 立即开奖（可指定ID）
                /cancel [ID] - 取消抽奖

                *使用流程：*
                1. 发送 /newlottery 开始创建抽奖
                2. 按提示输入抽奖标题、奖品、人数等
                3. 用户点击 *[参与抽奖]* 按钮报名
                4. 发送 /draw 开奖，机器人随机选取获奖者

                _机器人版本: v1.0.0_
                """;

        sendMarkdownMessage(message.getChatId(), helpText);
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
        if (session == null) {
            return;
        }

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

        String announcement = buildLotteryAnnouncement(lottery, 0);
        SendMessage msg = SendMessage.builder()
                .chatId(session.chatId.toString())
                .text(announcement)
                .parseMode(ParseMode.MARKDOWN)
                .replyMarkup(buildJoinKeyboard(lottery.getId()))
                .build();

        try {
            Message sent = execute(msg);
            // 保存消息ID
            lottery.setMessageId(sent.getMessageId());
            // 更新到数据库（通过service再次保存）
            log.info("Lottery announcement sent, messageId={}", sent.getMessageId());
        } catch (TelegramApiException e) {
            log.error("Failed to send lottery announcement", e);
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

        processJoin(chatId, activeLottery.get().getId(), message.getFrom());
    }

    private void processJoin(Long chatId, Long lotteryId, User user) {
        LotteryService.JoinResult result = lotteryService.joinLottery(
                lotteryId,
                user.getId(),
                user.getUserName(),
                getFullName(user)
        );

        String response = switch (result) {
            case SUCCESS ->
                    "*已成功参与抽奖！* 祝你好运！\n当前编号：" +
                    lotteryService.getParticipantCount(lotteryId);
            case ALREADY_JOINED ->
                    "你已经参与过这次抽奖了，不可重复参与。";
            case LOTTERY_ENDED ->
                    "该抽奖已结束，无法参与。";
            case LOTTERY_EXPIRED ->
                    "该抽奖已过截止时间，无法参与。";
            case NOT_FOUND ->
                    "未找到该抽奖活动。";
        };

        sendMarkdownMessage(chatId, response);
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
        if (!isAdmin && !lottery.getCreatorId().equals(userId)) {
            sendMarkdownMessage(chatId, "只有抽奖创建者或管理员才能执行开奖操作。");
            return;
        }

        executeDraw(chatId, lotteryId);
    }

    private void executeDraw(Long chatId, Long lotteryId) {
        // 发送开奖动画消息
        sendMarkdownMessage(chatId, "*正在开奖中... 请稍候*");

        LotteryService.DrawResult result = lotteryService.drawLottery(lotteryId, null);

        String response = switch (result.status) {
            case SUCCESS -> buildDrawResultMessage(result.lottery, result.winners);
            case NO_PARTICIPANTS -> "没有人参与本次抽奖，抽奖结束。";
            case ALREADY_DRAWN -> "该抽奖已经开奖过了。";
            case NOT_FOUND -> "未找到该抽奖活动。";
        };

        sendMarkdownMessage(chatId, response);
    }

    private String buildDrawResultMessage(Lottery lottery, List<Participant> winners) {
        StringBuilder sb = new StringBuilder();
        sb.append("*== 开奖结果公告 ==*\n\n");
        sb.append("*抽奖名称：*").append(escapeMarkdown(lottery.getTitle())).append("\n");
        sb.append("*奖品：*").append(escapeMarkdown(lottery.getPrize())).append("\n");
        sb.append("*开奖时间：*").append(lottery.getDrawnAt().format(FORMATTER)).append("\n\n");

        if (winners.isEmpty()) {
            sb.append("_没有参与者，无获奖者_");
        } else {
            sb.append("*恭喜以下用户获奖！*\n");
            for (int i = 0; i < winners.size(); i++) {
                Participant w = winners.get(i);
                sb.append(i + 1).append(". ");
                if (w.getUsername() != null && !w.getUsername().isEmpty()) {
                    sb.append("@").append(w.getUsername());
                } else {
                    sb.append(escapeMarkdown(w.getFullName()));
                }
                sb.append("\n");
            }
        }

        sb.append("\n_感谢所有参与者！_");
        return sb.toString();
    }

    // ==================== 取消抽奖 ====================

    private void handleCancelCommand(Message message) {
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
        } else {
            sendMarkdownMessage(chatId, "取消失败：你可能没有权限或抽奖已结束。");
        }
    }

    // ==================== 查看列表 ====================

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
        sb.append("*抽奖：*").append(escapeMarkdown(lottery.getTitle())).append("\n");
        sb.append("*总人数：*").append(participants.size()).append(" 人\n\n");

        if (participants.isEmpty()) {
            sb.append("_暂无人参与_");
        } else {
            for (int i = 0; i < participants.size(); i++) {
                Participant p = participants.get(i);
                sb.append(i + 1).append(". ");
                if (p.getUsername() != null && !p.getUsername().isEmpty()) {
                    sb.append("@").append(p.getUsername());
                } else {
                    sb.append(escapeMarkdown(p.getFullName()));
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
        String announcement = buildLotteryAnnouncement(lottery, (int) count);

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(announcement)
                .parseMode(ParseMode.MARKDOWN)
                .replyMarkup(buildJoinKeyboard(lottery.getId()))
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

        if (data.startsWith("join:")) {
            Long lotteryId = Long.parseLong(data.substring(5));
            processJoin(chatId, lotteryId, user);
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
                    "*[系统提示]* 抽奖 \"" + escapeMarkdown(lottery.getTitle()) +
                    "\" 已到截止时间，自动开奖中...");
            executeDraw(lottery.getChatId(), lottery.getId());
        }
    }

    // ==================== 工具方法 ====================

    private String buildLotteryAnnouncement(Lottery lottery, int participantCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("*== 抽奖活动 ==*\n\n");
        sb.append("*标题：*").append(escapeMarkdown(lottery.getTitle())).append("\n");
        sb.append("*奖品：*").append(escapeMarkdown(lottery.getPrize())).append("\n");
        sb.append("*获奖名额：*").append(lottery.getWinnerCount()).append(" 人\n");

        if (lottery.getDescription() != null && !lottery.getDescription().isEmpty()) {
            sb.append("*说明：*").append(escapeMarkdown(lottery.getDescription())).append("\n");
        }

        if (lottery.getEndTime() != null) {
            sb.append("*截止时间：*")
                    .append(lottery.getEndTime().format(FORMATTER)).append("\n");
        } else {
            sb.append("*截止时间：*手动开奖\n");
        }

        sb.append("*已参与：*").append(participantCount).append(" 人\n\n");
        sb.append("*创建者：*").append(escapeMarkdown(lottery.getCreatorName())).append("\n");
        sb.append("*抽奖ID：*`").append(lottery.getId()).append("`\n\n");
        sb.append("_点击下方按钮参与抽奖！_");
        return sb.toString();
    }

    private InlineKeyboardMarkup buildJoinKeyboard(Long lotteryId) {
        InlineKeyboardButton joinBtn = InlineKeyboardButton.builder()
                .text("参与抽奖")
                .callbackData("join:" + lotteryId)
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(joinBtn))
                .build();
    }

    private void sendMarkdownMessage(Long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode(ParseMode.MARKDOWN)
                .build();
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}: {}", chatId, e.getMessage());
        }
    }

    private String getFullName(User user) {
        String name = user.getFirstName();
        if (user.getLastName() != null && !user.getLastName().isEmpty()) {
            name += " " + user.getLastName();
        }
        return name;
    }

    private String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`");
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
}
