// repository/LotteryTemplateRepository.java
package com.lottery.bot.repository;

import com.lottery.bot.model.LotteryTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LotteryTemplateRepository extends JpaRepository<LotteryTemplate, Long> {

    List<LotteryTemplate> findByCreatorId(Long creatorId);

    List<LotteryTemplate> findAllByOrderByCreatedAtDesc();
}
