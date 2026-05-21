// service/LotteryService.java
package com.lottery.bot.service;

import com.lottery.bot.model.Lottery;
import com.lottery.bot.model.Participant;
import com.lottery.bot.repository.LotteryRepository;
import com.lottery.bot.repository.ParticipantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryService {

    private final LotteryRepository lotteryRepository;
    private final ParticipantRepository participantRepository;

    /**
     * 创建新抽奖
     */
    @Transactional
    public Lottery createLottery(Long chatId, Long creatorId, String creatorName,
                                  String title, String prize, int winnerCount,
                                  String description, LocalDateTime endTime) {
        Lottery lottery = Lottery.builder()
                .chatId(chatId)
                .creatorId(creatorId)
                .creatorName(creatorName)
                .title(title)
                .prize(prize)
                .winnerCount(winnerCount)
                .description(description)
                .endTime(endTime)
                .status(Lottery.LotteryStatus.ACTIVE)
                .build();

        Lottery saved = lotteryRepository.save(lottery);
        log.info("Created lottery [{}] in chat [{}] by [{}]", saved.getId(), chatId, creatorName);
        return saved;
    }

    /**
     * 参与抽奖
     */
    @Transactional
    public JoinResult joinLottery(Long lotteryId, Long userId,
                                   String username, String fullName) {
        Lottery lottery = lotteryRepository.findById(lotteryId)
                .orElse(null);

        if (lottery == null) {
            return JoinResult.NOT_FOUND;
        }
        if (lottery.getStatus() != Lottery.LotteryStatus.ACTIVE) {
            return JoinResult.LOTTERY_ENDED;
        }
        if (lottery.getEndTime() != null && lottery.getEndTime().isBefore(LocalDateTime.now())) {
            return JoinResult.LOTTERY_EXPIRED;
        }

        // 检查是否已参与
        Optional<Participant> existing = participantRepository
                .findByLotteryIdAndUserId(lotteryId, userId);
        if (existing.isPresent()) {
            return JoinResult.ALREADY_JOINED;
        }

        Participant participant = Participant.builder()
                .lottery(lottery)
                .userId(userId)
                .username(username)
                .fullName(fullName)
                .winner(false)
                .build();

        participantRepository.save(participant);
        log.info("User [{}] joined lottery [{}]", userId, lotteryId);
        return JoinResult.SUCCESS;
    }

    /**
     * 执行开奖
     */
    @Transactional
    public DrawResult drawLottery(Long lotteryId, Long operatorId) {
        Lottery lottery = lotteryRepository.findById(lotteryId)
                .orElse(null);

        if (lottery == null) {
            return new DrawResult(DrawStatus.NOT_FOUND, null, null);
        }
        if (lottery.getStatus() != Lottery.LotteryStatus.ACTIVE) {
            return new DrawResult(DrawStatus.ALREADY_DRAWN, null, null);
        }

        List<Participant> participants = participantRepository.findByLotteryId(lotteryId);
        if (participants.isEmpty()) {
            return new DrawResult(DrawStatus.NO_PARTICIPANTS, lottery, Collections.emptyList());
        }

        // 获取历史上中过奖的用户ID列表
        List<Long> pastWinnerIds = participantRepository.findDistinctUserIdByWinnerTrue();

        // 过滤掉历史上中过奖的用户
        List<Participant> eligibleParticipants = participants.stream()
                .filter(p -> !pastWinnerIds.contains(p.getUserId()))
                .collect(Collectors.toList());

        if (eligibleParticipants.isEmpty()) {
            log.warn("Lottery [{}] has no eligible participants (all past winners)", lotteryId);
            return new DrawResult(DrawStatus.NO_ELIGIBLE_PARTICIPANTS, lottery, Collections.emptyList());
        }

        // 随机抽取获奖者
        int actualWinners = Math.min(lottery.getWinnerCount(), eligibleParticipants.size());
        Collections.shuffle(eligibleParticipants, new SecureRandom());
        List<Participant> winners = eligibleParticipants.subList(0, actualWinners);

        // 标记获奖者
        winners.forEach(w -> {
            w.setWinner(true);
            participantRepository.save(w);
        });

        // 更新抽奖状态
        lottery.setStatus(Lottery.LotteryStatus.DRAWN);
        lottery.setDrawnAt(LocalDateTime.now());
        lotteryRepository.save(lottery);

        log.info("Lottery [{}] drawn, winners count: {}", lotteryId, winners.size());
        return new DrawResult(DrawStatus.SUCCESS, lottery, winners);
    }

    /**
     * 取消抽奖
     */
    @Transactional
    public boolean cancelLottery(Long lotteryId, Long operatorId, boolean isAdmin) {
        Lottery lottery = lotteryRepository.findById(lotteryId).orElse(null);
        if (lottery == null) {
            return false;
        }
        if (!isAdmin && !lottery.getCreatorId().equals(operatorId)) {
            return false;
        }
        if (lottery.getStatus() != Lottery.LotteryStatus.ACTIVE) {
            return false;
        }

        lottery.setStatus(Lottery.LotteryStatus.CANCELLED);
        lotteryRepository.save(lottery);
        log.info("Lottery [{}] cancelled by [{}]", lotteryId, operatorId);
        return true;
    }

    /**
     * 获取群组当前活跃抽奖
     */
    public Optional<Lottery> getActiveLottery(Long chatId) {
        return lotteryRepository.findFirstByChatIdAndStatusOrderByCreatedAtDesc(
                chatId, Lottery.LotteryStatus.ACTIVE);
    }

    /**
     * 获取参与者列表
     */
    public List<Participant> getParticipants(Long lotteryId) {
        return participantRepository.findByLotteryId(lotteryId);
    }

    /**
     * 获取参与人数
     */
    public long getParticipantCount(Long lotteryId) {
        return participantRepository.countByLotteryId(lotteryId);
    }

    /**
     * 获取所有过期抽奖（定时任务使用）
     */
    public List<Lottery> getExpiredLotteries() {
        return lotteryRepository.findExpiredLotteries(LocalDateTime.now());
    }

    public List<Lottery> getAllActiveLotteries() {
        return lotteryRepository.findByStatus(Lottery.LotteryStatus.ACTIVE);
    }

    public Optional<Lottery> getLotteryById(Long id) {
        return lotteryRepository.findById(id);
    }

    public List<Lottery> getAllLotteries() {
        return lotteryRepository.findAll();
    }

    @Transactional
    public void deleteLottery(Long id) {
        lotteryRepository.deleteById(id);
        participantRepository.deleteByLotteryId(id);
        log.info("Deleted lottery [{}]", id);
    }

    @Transactional
    public void setPrizeDistributed(Long lotteryId, boolean distributed) {
        Lottery lottery = lotteryRepository.findById(lotteryId).orElse(null);
        if (lottery != null) {
            lottery.setPrizeDistributed(distributed);
            lotteryRepository.save(lottery);
            log.info("Lottery [{}] prize distributed set to {}", lotteryId, distributed);
        }
    }

    // ========== 枚举定义 ==========

    public enum JoinResult {
        SUCCESS, ALREADY_JOINED, NOT_FOUND, LOTTERY_ENDED, LOTTERY_EXPIRED
    }

    public enum DrawStatus {
        SUCCESS, NOT_FOUND, ALREADY_DRAWN, NO_PARTICIPANTS, NO_ELIGIBLE_PARTICIPANTS
    }

    public static class DrawResult {
        public final DrawStatus status;
        public final Lottery lottery;
        public final List<Participant> winners;

        public DrawResult(DrawStatus status, Lottery lottery, List<Participant> winners) {
            this.status = status;
            this.lottery = lottery;
            this.winners = winners;
        }

        public DrawStatus getStatus() {
            return status;
        }

        public Lottery getLottery() {
            return lottery;
        }

        public List<Participant> getWinners() {
            return winners;
        }
    }
}