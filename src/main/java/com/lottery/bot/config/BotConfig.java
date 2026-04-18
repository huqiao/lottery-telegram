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
    private String lotteryGroupLanguages;
    private String lotteryGroupTimezones;
    private String language = "zh";

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

    public String getGroupLanguage(Long groupId) {
        if (lotteryGroupLanguages == null || lotteryGroupLanguages.isEmpty()) {
            return language;
        }
        String[] langConfigs = lotteryGroupLanguages.split(",");
        for (String config : langConfigs) {
            String[] parts = config.trim().split(":");
            if (parts.length == 2 && Long.parseLong(parts[0].trim()) == groupId) {
                return parts[1].trim();
            }
        }
        return language;
    }

    public String getGroupTimezone(Long groupId) {
        if (lotteryGroupTimezones == null || lotteryGroupTimezones.isEmpty()) {
            return "GMT+8";
        }
        String[] timezoneConfigs = lotteryGroupTimezones.split(",");
        for (String config : timezoneConfigs) {
            String[] parts = config.trim().split(":");
            if (parts.length == 2 && Long.parseLong(parts[0].trim()) == groupId) {
                return parts[1].trim();
            }
        }
        return "GMT+8";
    }

    public java.time.ZoneId getGroupZoneId(Long groupId) {
        String timezone = getGroupTimezone(groupId);
        if (timezone.startsWith("GMT")) {
            String offset = timezone.substring(3);
            int hours = 0;
            int minutes = 0;
            if (offset.contains(":")) {
                String[] parts = offset.split(":");
                hours = Integer.parseInt(parts[0]);
                minutes = Integer.parseInt(parts[1]);
            } else {
                hours = Integer.parseInt(offset);
            }
            return java.time.ZoneOffset.ofHoursMinutes(hours, minutes);
        }
        return java.time.ZoneId.of(timezone);
    }
}