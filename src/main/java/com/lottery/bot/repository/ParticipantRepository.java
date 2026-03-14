// repository/ParticipantRepository.java
package com.lottery.bot.repository;

import com.lottery.bot.model.Participant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParticipantRepository extends JpaRepository<Participant, Long> {

    // 检查用户是否已参与
    Optional<Participant> findByLotteryIdAndUserId(Long lotteryId, Long userId);

    // 获取抽奖的所有参与者
    List<Participant> findByLotteryId(Long lotteryId);

    // 获取获奖者列表
    List<Participant> findByLotteryIdAndWinnerTrue(Long lotteryId);

    // 统计参与人数
    long countByLotteryId(Long lotteryId);
}
