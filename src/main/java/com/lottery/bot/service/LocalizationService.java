package com.lottery.bot.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class LocalizationService {

    private final Map<String, Map<String, String>> translations = new HashMap<>();

    public LocalizationService() {
        initTranslations();
    }

    private void initTranslations() {
        Map<String, String> zh = new HashMap<>();
        zh.put("lottery_announcement", "🎉 *新抽奖活动开始！*\n\n*标题:* %s\n*奖品:* %s\n*获奖名额:* %d人\n*截止时间:* %s\n\n点击下方按钮参与抽奖！");
        zh.put("join_success", "*已成功参与抽奖！* 🎉\n当前编号：%d");
        zh.put("join_already", "你已经参与过这次抽奖了，不可重复参与。");
        zh.put("join_ended", "该抽奖已结束，无法参与。");
        zh.put("join_expired", "该抽奖已过截止时间，无法参与。");
        zh.put("join_not_found", "未找到该抽奖活动。");
        zh.put("winner_announced", "🏆 *开奖公告*\n\n*抽奖:* %s\n*奖品:* %s\n\n*中奖者:*\n%s\n\n恭喜以上幸运用户！");
        zh.put("no_participants", "⚠️ 抽奖 \"%s\" 没有人参与，无法开奖。");
        zh.put("no_winners", "⚠️ 抽奖 \"%s\" 没有人参与抽奖。");
        zh.put("lottery_active", "✅ 抽奖进行中");
        zh.put("lottery_drawn", "🏁 抽奖已结束");
        zh.put("lottery_cancelled", "❌ 抽奖已取消");
        zh.put("manual_draw", "手动开奖");
        zh.put("participants_count", "*已参与：*%d 人");
        zh.put("creator", "*创建者：*");
        zh.put("lottery_id", "*抽奖ID：*`");
        zh.put("join_prompt", "点击下方按钮参与抽奖！");
        zh.put("join_lottery", "参与抽奖");
        zh.put("join_error", "参与抽奖时发生错误，请稍后重试。");
        zh.put("draw_in_progress", "*正在开奖中... 请稍候*");
        zh.put("no_participants_draw", "没有人参与本次抽奖，抽奖结束。");
        zh.put("already_drawn", "该抽奖已经开奖过了。");
        zh.put("lottery_not_found", "未找到该抽奖活动。");
        zh.put("draw_result_title", "*== 开奖结果公告 ==*");
        zh.put("lottery_name", "*抽奖名称：*");
        zh.put("draw_prize", "*奖品：*");
        zh.put("draw_time", "*开奖时间：*");
        zh.put("winners_label", "*中奖者：*");
        zh.put("no_winners_text", "_没有参与者，无获奖者_");
        zh.put("congratulations", "恭喜以上幸运用户！");
        translations.put("zh", zh);

        Map<String, String> en = new HashMap<>();
        en.put("lottery_announcement", "🎉 *New Lottery Started!*\n\n*Title:* %s\n*Prize:* %s\n*Winners:* %d\n*End Time:* %s\n\nClick the button below to join!");
        en.put("join_success", "*Successfully joined the lottery!* 🎉\nYour number: %d");
        en.put("join_already", "You have already joined this lottery.");
        en.put("join_ended", "This lottery has ended.");
        en.put("join_expired", "This lottery has expired.");
        en.put("join_not_found", "Lottery not found.");
        en.put("winner_announced", "🏆 *Lottery Results*\n\n*Lottery:* %s\n*Prize:* %s\n\n*Winners:*\n%s\n\nCongratulations to the winners!");
        en.put("no_participants", "⚠️ No one participated in lottery \"%s\", cannot draw.");
        en.put("no_winners", "⚠️ No one participated in lottery \"%s\".");
        en.put("lottery_active", "✅ Lottery in progress");
        en.put("lottery_drawn", "🏁 Lottery ended");
        en.put("lottery_cancelled", "❌ Lottery cancelled");
        en.put("manual_draw", "Manual draw");
        en.put("participants_count", "*Participants:* %d");
        en.put("creator", "*Creator:* ");
        en.put("lottery_id", "*Lottery ID:* `");
        en.put("join_prompt", "Click the button below to join!");
        en.put("join_lottery", "Join Lottery");
        en.put("join_error", "An error occurred while joining. Please try again later.");
        en.put("draw_in_progress", "*Drawing in progress... Please wait*");
        en.put("no_participants_draw", "No one participated in this lottery. Lottery ended.");
        en.put("already_drawn", "This lottery has already been drawn.");
        en.put("lottery_not_found", "Lottery not found.");
        en.put("draw_result_title", "*== Draw Results ==*");
        en.put("lottery_name", "*Lottery Name:* ");
        en.put("draw_prize", "*Prize:* ");
        en.put("draw_time", "*Draw Time:* ");
        en.put("winners_label", "*Winners:* ");
        en.put("no_winners_text", "_No participants, no winners_");
        en.put("congratulations", "Congratulations to the winners!");
        translations.put("en", en);

        Map<String, String> uz = new HashMap<>();
        uz.put("lottery_announcement", "🎉 *Yangi Lottery Boshlandi!*\n\n*Nom:* %s\n*Mukofot:* %s dona g'olib\n*Muddati:* %s\n\nPastdagi tugmachani bosing!");
        uz.put("join_success", "*Lotteryga muvaffaqiyatli qo'shildingiz!* 🎉\nSizning raqamingiz: %d");
        uz.put("join_already", "Siz allaqachon bu loteriyaga qo'shilgansiz.");
        uz.put("join_ended", "Bu lottery tugadi.");
        uz.put("join_expired", "Bu lotterning muddati tugadi.");
        uz.put("join_not_found", "Lottery topilmadi.");
        uz.put("winner_announced", "🏆 *Lottery Natijalari*\n\n*Lottery:* %s\n*Mukofot:* %s\n\n*G'oliblar:*\n%s\n\nG'oliblarga tabriklar!");
        uz.put("no_participants", "⚠️ \"%s\" lotteryda hech kim ishtirok etmadi, chizish mumkin emas.");
        uz.put("no_winners", "⚠️ \"%s\" lotteryda hech kim ishtirok etmadi.");
        uz.put("lottery_active", "✅ Lottery davom etmoqda");
        uz.put("lottery_drawn", "🏁 Lottery tugadi");
        uz.put("lottery_cancelled", "❌ Lottery bekor qilindi");
        uz.put("manual_draw", "Qo'l bilan chizish");
        uz.put("participants_count", "*Ishtirokchilar:* %d");
        uz.put("creator", "*Yaratuvchi:* ");
        uz.put("lottery_id", "*Lottery ID:* `");
        uz.put("join_prompt", "Pastdagi tugmachani bosing!");
        uz.put("join_lottery", "Lotteryga qo'shiling");
        uz.put("join_error", "Xatolik yuz berdi. Iltimos, keyinroq qayta urinib ko'ring.");
        uz.put("draw_in_progress", "*Chizish jarayoni... Iltimos kuting*");
        uz.put("no_participants_draw", "Bu lotteryda hech kim ishtirok etmadi. Lottery tugadi.");
        uz.put("already_drawn", "Bu lottery allaqachon chizilgan.");
        uz.put("lottery_not_found", "Lottery topilmadi.");
        uz.put("draw_result_title", "*== Natijalar ==*");
        uz.put("lottery_name", "*Lottery nomi:* ");
        uz.put("draw_prize", "*Mukofot:* ");
        uz.put("draw_time", "*Chizish vaqti:* ");
        uz.put("winners_label", "*G'oliblar:* ");
        uz.put("no_winners_text", "_Ishtirokchilar yo'q, g'oliblar yo'q_");
        uz.put("congratulations", "G'oliblarga tabriklar!");
        translations.put("uz", uz);
    }

    public String get(String key, String language) {
        Map<String, String> langMap = translations.getOrDefault(language, translations.get("zh"));
        return langMap.getOrDefault(key, translations.get("zh").get(key));
    }

    public String get(String key, String language, Object... args) {
        String template = get(key, language);
        return String.format(template, args);
    }
}
