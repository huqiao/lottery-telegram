// repository/LotteryRepository.java
package com.lottery.bot.repository;

import com.lottery.bot.model.Lottery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LotteryRepository extends JpaRepository<Lottery, Long> {

    // 查找群组中进行中的抽奖
    List<Lottery> findByChatIdAndStatus(Long chatId, Lottery.LotteryStatus status);

    // 查找群组中最新的进行中抽奖
    Optional<Lottery> findFirstByChatIdAndStatusOrderByCreatedAtDesc(
            Long chatId, Lottery.LotteryStatus status);

    // 查找所有已超时但未开奖的抽奖（用于定时任务）
    @Query("SELECT l FROM Lottery l WHERE l.status = 'ACTIVE' " +
           "AND l.endTime IS NOT NULL AND l.endTime <= :now")
    List<Lottery> findExpiredLotteries(LocalDateTime now);

    // 按创建者查找
    List<Lottery> findByCreatorIdAndStatus(Long creatorId, Lottery.LotteryStatus status);

    // 按状态查找所有（管理员用）
    List<Lottery> findByStatus(Lottery.LotteryStatus status);
}
