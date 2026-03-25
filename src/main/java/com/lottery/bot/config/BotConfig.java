// config/BotConfig.java
package com.lottery.bot.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Configuration
@ConfigurationProperties(prefix = "telegram.bot")
@Slf4j
public class BotConfig {

    private String token;
    private String username;
    private Long adminId;
    private Long adminGroupId;
    private String lotteryGroupIds;

    public List<Long> getLotteryGroupIds() {
        if (lotteryGroupIds == null || lotteryGroupIds.isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(lotteryGroupIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }
}