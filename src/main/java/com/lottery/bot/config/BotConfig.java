// config/BotConfig.java
package com.lottery.bot.config;
import com.lottery.bot.LotteryBot;
import com.lottery.bot.service.LotteryService;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
@Data
@Configuration
@ConfigurationProperties(prefix = "telegram.bot")
@Slf4j
public class BotConfig {

    private String token;
    private String username;
    private Long adminId;


//    @Bean
//    public LotteryBot lotteryBot(LotteryService lotteryService) {
//        return new LotteryBot(this, lotteryService);
//    }
//
//    @PostConstruct
//    public void init() {
//        try {
//            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
//            LotteryBot bot = new LotteryBot(this, null);
//            botsApi.registerBot(bot);
//            log.info("Telegram Bot [{}] started successfully!", username);
//        } catch (TelegramApiException e) {
//            log.error("Failed to start Telegram Bot", e);
//        }
//    }
}
