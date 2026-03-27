// service/LotteryTemplateService.java
package com.lottery.bot.service;

import com.lottery.bot.model.LotteryTemplate;
import com.lottery.bot.repository.LotteryTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryTemplateService {

    private final LotteryTemplateRepository templateRepository;

    public List<LotteryTemplate> getAllTemplates() {
        return templateRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<LotteryTemplate> getTemplatesByCreator(Long creatorId) {
        return templateRepository.findByCreatorId(creatorId);
    }

    public Optional<LotteryTemplate> getTemplateById(Long id) {
        return templateRepository.findById(id);
    }

    @Transactional
    public LotteryTemplate createTemplate(Long creatorId, String creatorName,
                                           String title, String description,
                                           String prize, int winnerCount,
                                           Integer defaultEndHours) {
        LotteryTemplate template = LotteryTemplate.builder()
                .creatorId(creatorId)
                .creatorName(creatorName)
                .title(title)
                .description(description)
                .prize(prize)
                .winnerCount(winnerCount)
                .defaultEndHours(defaultEndHours)
                .build();

        LotteryTemplate saved = templateRepository.save(template);
        log.info("Created template [{}] by [{}]", saved.getId(), creatorName);
        return saved;
    }

    @Transactional
    public Optional<LotteryTemplate> updateTemplate(Long id, Long userId, boolean isAdmin,
                                                      String title, String description,
                                                      String prize, int winnerCount,
                                                      Integer defaultEndHours) {
        Optional<LotteryTemplate> optTemplate = templateRepository.findById(id);
        if (optTemplate.isEmpty()) {
            return Optional.empty();
        }

        LotteryTemplate template = optTemplate.get();
        if (!isAdmin && !template.getCreatorId().equals(userId)) {
            return Optional.empty();
        }

        if (title != null) template.setTitle(title);
        if (description != null) template.setDescription(description);
        if (prize != null) template.setPrize(prize);
        if (winnerCount > 0) template.setWinnerCount(winnerCount);
        if (defaultEndHours != null) template.setDefaultEndHours(defaultEndHours);

        return Optional.of(templateRepository.save(template));
    }

    @Transactional
    public boolean deleteTemplate(Long id, Long userId, boolean isAdmin) {
        Optional<LotteryTemplate> optTemplate = templateRepository.findById(id);
        if (optTemplate.isEmpty()) {
            return false;
        }

        LotteryTemplate template = optTemplate.get();
        if (!isAdmin && !template.getCreatorId().equals(userId)) {
            return false;
        }

        templateRepository.delete(template);
        log.info("Deleted template [{}]", id);
        return true;
    }
}
