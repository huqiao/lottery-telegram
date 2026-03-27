// model/Lottery.java
package com.lottery.bot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "lotteries")
public class Lottery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;           // 抽奖标题

    @Column(length = 1000)
    private String description;     // 抽奖描述

    private String prize;           // 奖品描述

    @Column(name = "winner_count")
    private int winnerCount;        // 获奖名额数量

    @Column(name = "chat_id")
    private Long chatId;            // 所在群组 ID

    @Column(name = "creator_id")
    private Long creatorId;         // 创建者 ID

    @Column(name = "creator_name")
    private String creatorName;     // 创建者名称

    @Enumerated(EnumType.STRING)
    private LotteryStatus status;   // 抽奖状态

    @Column(name = "end_time")
    private LocalDateTime endTime;  // 截止时间

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "drawn_at")
    private LocalDateTime drawnAt;  // 开奖时间

    @OneToMany(mappedBy = "lottery", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Participant> participants = new ArrayList<>();

    @Column(name = "message_id")
    private Integer messageId;      // 公告消息 ID（用于置顶/更新）

     @Column(name = "prize_distributed")
     private boolean prizeDistributed;  // 奖项是否已发放

    public enum LotteryStatus {
        ACTIVE,    // 进行中
        DRAWN,     // 已开奖
        CANCELLED  // 已取消
    }

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = LotteryStatus.ACTIVE;
        }
    }
}
