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
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.lottery.bot.service.LotteryService.DrawStatus.*;

@Slf4j
@Component
public class LotteryBot extends TelegramLongPollingBot {

    private final BotConfig botConfig;
    private final LotteryService lotteryService;

    // 多步骤创建抽奖状态机（管理员用户ID -> 当前步骤）
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

    // ==================== 命令路由（区分私聊/群聊） ====================

    private void handleCommand(Message message) {
        String text = message.getText();
        String command = text.split(" ")[0].toLowerCase()
                .replace("@" + botConfig.getUsername().toLowerCase(), "");

        boolean isPrivate = isPrivateChat(message);
        boolean isAdmin = isAdmin(message.getFrom().getId());

        if (isPrivate) {
            // 私聊命令
            switch (command) {
                case "/start", "/help" -> sendPrivateHelp(message, isAdmin);
                case "/newlottery" -> {
                    if (isAdmin) startCreateLottery(message);
                    else sendMarkdownMessage(message.getChatId(), "此命令仅限管理员在私聊中使用。");
                }
                case "/draw" -> {
                    if (isAdmin) handleDrawCommand(message);
                    else sendMarkdownMessage(message.getChatId(), "此命令仅限管理员在私聊中使用。");
                }
                case "/cancel" -> {
                    if (isAdmin) handleCancelCommand(message);
                    else sendMarkdownMessage(message.getChatId(), "此命令仅限管理员在私聊中使用。");
                }
                case "/list" -> {
                    if (isAdmin) handleListCommand(message);
                    else sendMarkdownMessage(message.getChatId(), "此命令仅限管理员在私聊中使用。");
                }
                case "/lotteries" -> {
                    if (isAdmin) handleLotteriesCommand(message);
                    else sendMarkdownMessage(message.getChatId(), "此命令仅限管理员在私聊中使用。");
                }
                default -> { /* 忽略未知命令 */ }
            }
        } else {
            // 群聊命令（普通用户）
            switch (command) {
                case "/start", "/help" -> sendGroupHelp(message);
                case "/join" -> handleJoinCommand(message);
                case "/info" -> handleInfoCommand(message);
                default -> { /* 忽略未知命令 */ }
            }
        }
    }

    // ==================== /help ====================

    private void sendPrivateHelp(Message message, boolean isAdmin) {
        String helpText;
        if (isAdmin) {
            helpText = """
                    *== Lottery Bot 管理员控制台 ==*

                    *抽奖管理命令（私聊使用）：*
                    /newlottery \\- 在指定群组创建新抽奖
                    /lotteries \\- 查看所有进行中的抽奖
                    /list <ID> \\- 查看指定抽奖的参与者列表
                    /draw <ID> \\- 立即开奖
                    /cancel <ID> \\- 取消抽奖

                    *使用流程：*
                    1\\. 私聊发送 /newlottery，按提示填写抽奖信息
                    2\\. 输入目标群组 ID，机器人将在群内发布抽奖公告
                    3\\. 群内用户点击按钮参与抽奖
                    4\\. 私聊发送 /draw <ID> 开奖

                    _机器人版本: v2\\.0\\.0_
                    """;
        } else {
            helpText = """
                    *== Lottery Bot 抽奖机器人 ==*

                    请前往群组参与抽奖活动！

                    *群内用户命令：*
                    /join \\- 参与当前抽奖
                    /info \\- 查看当前抽奖信息

                    _机器人版本: v2\\.0\\.0_
                    """;
        }
        sendMarkdownV2Message(message.getChatId(), helpText);
    }

    private void sendGroupHelp(Message message) {
        String helpText = """
                *== Lottery Bot 抽奖机器人 ==*

                *用户命令：*
                /join - 参与当前抽奖
                /info - 查看当前抽奖信息

                _等待管理员创建抽奖活动后，点击参与按钮或发送 /join 即可报名！_
                """;
        sendMarkdownMessage(message.getChatId(), helpText);
    }

    // ==================== 创建抽奖（管理员私聊，状态机） ====================

    private void startCreateLottery(Message message) {
        Long userId = message.getFrom().getId();
        Long adminChatId = message.getChatId();

        CreateLotterySession session = new CreateLotterySession();
        session.adminChatId = adminChatId;
        session.creatorId = userId;
        session.creatorName = getFullName(message.getFrom());
        session.step = CreateStep.GROUP_ID;
        createSessions.put(userId, session);

        sendMarkdownMessage(adminChatId,
                "*[创建抽奖]* 第 1/6 步\n\n请输入目标 *群组 ID*：\n\n" +
                "_获取方法：将机器人添加到目标群组，然后转发一条群消息给 @userinfobot 获取群 ID（通常为负数，例如：\\-1001234567890）_");
    }

    private void handleTextInput(Message message) {
        // 只处理私聊中的文字输入（管理员创建抽奖）
        if (!isPrivateChat(message)) {
            return;
        }

        Long userId = message.getFrom().getId();
        CreateLotterySession session = createSessions.get(userId);
        if (session == null) {
            return;
        }

        String input = message.getText().trim();

        switch (session.step) {
            case GROUP_ID -> {
                try {
                    long groupId = Long.parseLong(input.replace(" ", ""));
                    session.targetChatId = groupId;
                    session.step = CreateStep.TITLE;
                    sendMarkdownMessage(session.adminChatId,
                            "*[创建抽奖]* 第 2/6 步\n\n请输入抽奖 *标题*：\n\n_例如：双十一大抽奖_");
                } catch (NumberFormatException e) {
                    sendMarkdownMessage(session.adminChatId,
                            "群组 ID 格式错误，请输入正确的群组 ID（例如：-1001234567890）：");
                }
            }
            case TITLE -> {
                session.title = input;
                session.step = CreateStep.PRIZE;
                sendMarkdownMessage(session.adminChatId,
                        "*[创建抽奖]* 第 3/6 步\n\n请输入 *奖品描述*：\n\n_例如：iPhone 16 Pro x1_");
            }
            case PRIZE -> {
                session.prize = input;
                session.step = CreateStep.WINNER_COUNT;
                sendMarkdownMessage(session.adminChatId,
                        "*[创建抽奖]* 第 4/6 步\n\n请输入 *获奖名额数量*（数字）：\n\n_例如：3_");
            }
            case WINNER_COUNT -> {
                try {
                    int count = Integer.parseInt(input);
                    if (count < 1 || count > 100) {
                        throw new NumberFormatException();
                    }
                    session.winnerCount = count;
                    session.step = CreateStep.DESCRIPTION;
                    sendMarkdownMessage(session.adminChatId,
                            "*[创建抽奖]* 第 5/6 步\n\n请输入 *抽奖说明*（可选，输入 \"无\" 跳过）：");
                } catch (NumberFormatException e) {
                    sendMarkdownMessage(session.adminChatId,
                            "请输入 1-100 之间的有效数字，重新输入获奖名额：");
                }
            }
            case DESCRIPTION -> {
                session.description = input.equals("无") ? "" : input;
                session.step = CreateStep.END_TIME;
                sendMarkdownMessage(session.adminChatId,
                        """
                        *[创建抽奖]* 第 6/6 步

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
                            sendMarkdownMessage(session.adminChatId,
                                    "截止时间不能早于当前时间，请重新输入：");
                            return;
                        }
                    } catch (Exception e) {
                        sendMarkdownMessage(session.adminChatId,
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
                session.targetChatId,
                session.creatorId,
                session.creatorName,
                session.title,
                session.prize,
                session.winnerCount,
                session.description,
                session.endTime
        );

        // 在目标群组发布抽奖公告
        String announcement = buildLotteryAnnouncement(lottery, 0);
        SendMessage msg = SendMessage.builder()
                .chatId(session.targetChatId.toString())
                .text(announcement)
                .parseMode(ParseMode.MARKDOWN)
                .replyMarkup(buildJoinKeyboard(lottery.getId()))
                .build();

        try {
            Message sent = execute(msg);
            lottery.setMessageId(sent.getMessageId());
            log.info("Lottery announcement sent to group {}, messageId={}", session.targetChatId, sent.getMessageId());
        } catch (TelegramApiException e) {
            log.error("Failed to send lottery announcement to group {}", session.targetChatId, e);
            sendMarkdownMessage(session.adminChatId,
                    "*[错误]* 无法发送公告到群组 `" + session.targetChatId +
                    "`，请确认机器人已加入该群组并有发言权限。\n抽奖 ID：`" + lottery.getId() + "`");
            return;
        }

        // 通知管理员创建成功
        sendMarkdownMessage(session.adminChatId,
                "*[创建成功]* 抽奖已发布到群组！\n\n" +
                "*抽奖标题：*" + escapeMarkdown(lottery.getTitle()) + "\n" +
                "*抽奖 ID：*`" + lottery.getId() + "`\n" +
                "*目标群组：*`" + session.targetChatId + "`\n\n" +
                "使用 `/draw " + lottery.getId() + "` 开奖\n" +
                "使用 `/cancel " + lottery.getId() + "` 取消抽奖");
    }

    // ==================== 群内参与抽奖 ====================

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

    // ==================== 开奖（管理员私聊） ====================

    private void handleDrawCommand(Message message) {
        Long adminChatId = message.getChatId();

        String[] parts = message.getText().split(" ");
        if (parts.length < 2) {
            sendMarkdownMessage(adminChatId,
                    "请指定抽奖 ID：`/draw <抽奖ID>`\n\n使用 /lotteries 查看所有进行中的抽奖。");
            return;
        }

        Long lotteryId;
        try {
            lotteryId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            sendMarkdownMessage(adminChatId, "抽奖 ID 格式错误，请输入数字。");
            return;
        }

        Lottery lottery = lotteryService.getLotteryById(lotteryId).orElse(null);
        if (lottery == null) {
            sendMarkdownMessage(adminChatId, "未找到该抽奖活动。");
            return;
        }

        executeDraw(lottery.getChatId(), lotteryId, adminChatId);
    }

    private void executeDraw(Long groupChatId, Long lotteryId, Long adminChatId) {
        sendMarkdownMessage(groupChatId, "*正在开奖中... 请稍候*");

        LotteryService.DrawResult result = lotteryService.drawLottery(lotteryId, null);

        switch (result.status) {
            case SUCCESS -> {
                String resultMsg = buildDrawResultMessage(result.lottery, result.winners);
                sendMarkdownMessage(groupChatId, resultMsg);
                if (adminChatId != null) {
                    sendMarkdownMessage(adminChatId, "*开奖成功！* 结果已发送至群组。");
                }
            }
            case NO_PARTICIPANTS -> {
                sendMarkdownMessage(groupChatId, "没有人参与本次抽奖，抽奖结束。");
                if (adminChatId != null) {
                    sendMarkdownMessage(adminChatId, "开奖完成：无人参与。");
                }
            }
            case ALREADY_DRAWN -> {
                if (adminChatId != null) {
                    sendMarkdownMessage(adminChatId, "该抽奖已经开奖过了。");
                }
            }
            case NOT_FOUND -> {
                if (adminChatId != null) {
                    sendMarkdownMessage(adminChatId, "未找到该抽奖活动。");
                }
            }
        }
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

    // ==================== 取消抽奖（管理员私聊） ====================

    private void handleCancelCommand(Message message) {
        Long adminChatId = message.getChatId();
        Long userId = message.getFrom().getId();
        boolean isAdmin = isAdmin(userId);

        String[] parts = message.getText().split(" ");
        if (parts.length < 2) {
            sendMarkdownMessage(adminChatId,
                    "请指定抽奖 ID：`/cancel <抽奖ID>`\n\n使用 /lotteries 查看所有进行中的抽奖。");
            return;
        }

        Long lotteryId;
        try {
            lotteryId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            sendMarkdownMessage(adminChatId, "抽奖 ID 格式错误，请输入数字。");
            return;
        }

        Lottery lottery = lotteryService.getLotteryById(lotteryId).orElse(null);
        if (lottery == null) {
            sendMarkdownMessage(adminChatId, "未找到该抽奖活动。");
            return;
        }

        Long groupChatId = lottery.getChatId();
        String lotteryTitle = lottery.getTitle();

        boolean cancelled = lotteryService.cancelLottery(lotteryId, userId, isAdmin);
        if (cancelled) {
            sendMarkdownMessage(adminChatId, "*抽奖已成功取消。*");
            sendMarkdownMessage(groupChatId,
                    "*[系统通知]* 抽奖「" + escapeMarkdown(lotteryTitle) + "」已被取消。");
        } else {
            sendMarkdownMessage(adminChatId, "取消失败：你可能没有权限或抽奖已结束。");
        }
    }

    // ==================== 查看参与者列表（管理员私聊） ====================

    private void handleListCommand(Message message) {
        Long adminChatId = message.getChatId();

        String[] parts = message.getText().split(" ");
        if (parts.length < 2) {
            sendMarkdownMessage(adminChatId,
                    "请指定抽奖 ID：`/list <抽奖ID>`\n\n使用 /lotteries 查看所有进行中的抽奖。");
            return;
        }

        Long lotteryId;
        try {
            lotteryId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            sendMarkdownMessage(adminChatId, "抽奖 ID 格式错误，请输入数字。");
            return;
        }

        Lottery lottery = lotteryService.getLotteryById(lotteryId).orElse(null);
        if (lottery == null) {
            sendMarkdownMessage(adminChatId, "未找到该抽奖活动。");
            return;
        }

        List<Participant> participants = lotteryService.getParticipants(lotteryId);

        StringBuilder sb = new StringBuilder();
        sb.append("*== 参与者列表 ==*\n");
        sb.append("*抽奖：*").append(escapeMarkdown(lottery.getTitle())).append("\n");
        sb.append("*状态：*").append(lottery.getStatus().name()).append("\n");
        sb.append("*总人数：*").append(participants.size()).append(" 人\n\n");

        if (participants.isEmpty()) {
            sb.append("_暂无人参与_");
        } else {
            for (int i = 0; i < Math.min(participants.size(), 50); i++) {
                Participant p = participants.get(i);
                sb.append(i + 1).append(". ");
                if (p.getUsername() != null && !p.getUsername().isEmpty()) {
                    sb.append("@").append(p.getUsername());
                } else {
                    sb.append(escapeMarkdown(p.getFullName()));
                }
                sb.append("\n");
            }
            if (participants.size() > 50) {
                sb.append("... 等 ").append(participants.size()).append(" 人");
            }
        }

        sendMarkdownMessage(adminChatId, sb.toString());
    }

    // ==================== 查看所有抽奖（管理员私聊） ====================

    private void handleLotteriesCommand(Message message) {
        Long adminChatId = message.getChatId();
        List<Lottery> lotteries = lotteryService.getAllActiveLotteries();

        if (lotteries.isEmpty()) {
            sendMarkdownMessage(adminChatId, "当前没有进行中的抽奖活动。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("*== 进行中的抽奖 ==*\n\n");

        for (Lottery l : lotteries) {
            sb.append("*ID：*`").append(l.getId()).append("`\n");
            sb.append("*标题：*").append(escapeMarkdown(l.getTitle())).append("\n");
            sb.append("*奖品：*").append(escapeMarkdown(l.getPrize())).append("\n");
            sb.append("*群组：*`").append(l.getChatId()).append("`\n");
            sb.append("*参与人数：*").append(lotteryService.getParticipantCount(l.getId())).append(" 人\n");
            if (l.getEndTime() != null) {
                sb.append("*截止：*").append(l.getEndTime().format(FORMATTER)).append("\n");
            } else {
                sb.append("*截止：*手动开奖\n");
            }
            sb.append("\\-\\-\\-\n");
        }

        sendMarkdownMessage(adminChatId, sb.toString());
    }

    // ==================== 群内查看抽奖信息 ====================

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

    // ==================== Callback 处理（群内点击参与按钮） ====================

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
                    "*[系统提示]* 抽奖「" + escapeMarkdown(lottery.getTitle()) +
                    "」已到截止时间，自动开奖中...");
            executeDraw(lottery.getChatId(), lottery.getId(), null);
        }
    }

    // ==================== 工具方法 ====================

    private boolean isPrivateChat(Message message) {
        return "private".equals(message.getChat().getType());
    }

    private boolean isAdmin(Long userId) {
        return userId.equals(botConfig.getAdminId());
    }

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
                .text("🎉 参与抽奖")
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

    private void sendMarkdownV2Message(Long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode(ParseMode.MARKDOWNV2)
                .build();
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send MarkdownV2 message to chat {}: {}", chatId, e.getMessage());
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
        Long adminChatId;    // 管理员的私聊 ID
        Long targetChatId;   // 目标群组 ID
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
        GROUP_ID, TITLE, PRIZE, WINNER_COUNT, DESCRIPTION, END_TIME
    }
}
