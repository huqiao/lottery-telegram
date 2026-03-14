// model/Participant.java
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
@Table(name = "participants",
       uniqueConstraints = @UniqueConstraint(columnNames = {"lottery_id", "user_id"}))
public class Participant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lottery_id", nullable = false)
    private Lottery lottery;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "username")
    private String username;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    private boolean winner;   // 是否是获奖者

    @PrePersist
    public void prePersist() {
        this.joinedAt = LocalDateTime.now();
    }
}
