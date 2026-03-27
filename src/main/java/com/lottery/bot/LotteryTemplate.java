// model/LotteryTemplate.java
package com.lottery.bot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "lottery_templates")
public class LotteryTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;           // 模板标题

    @Column(length = 1000)
    private String description;     // 模板描述

    private String prize;           // 奖品描述

    @Column(name = "winner_count")
    private int winnerCount;       // 获奖名额数量

    @Column(name = "creator_id")
    private Long creatorId;        // 创建者 ID

    @Column(name = "creator_name")
    private String creatorName;     // 创建者名称

    @Column(name = "default_end_hours")
    private Integer defaultEndHours;  // 默认截止时长（小时）

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
