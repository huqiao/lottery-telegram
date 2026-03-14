// LotteryBotApplication.java
package com.lottery.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LotteryBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(LotteryBotApplication.class, args);
    }
}
