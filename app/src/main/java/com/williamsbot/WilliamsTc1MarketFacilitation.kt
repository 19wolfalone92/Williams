package com.williamsbot

import org.json.JSONObject
import java.util.Locale

/**
 * TC1 Russian PDF pp.42–45, chapter 6:
 * Market Facilitation Index = (High-Low)/Volume; four windows are
 * comparisons of BOTH MFI and tick volume against the immediately prior bar:
 *
 * GREEN (+V,+MFI); FADE (-V,-MFI);
 * FAKE (-V,+MFI); SQUAT (+V,-MFI).
 *
 * Exchange klines expose BASE-ASSET volume, NOT the original traded
 * futures PIT tick-volume units. Thus the ratio is a properly labelled
 * BASE_VOLUME_PROXY, never a verified canonical TC1 signal.
 * Equality, zero-volume, missing/partial/gapped bars must not be guessed.
 * Book p45 explicitly says a squat also occurs MID-trend; NEVER infer
 * mandatory trend exhaustion, automatic TC2 entry, exit, or STOP move.
 */
internal fun williamsTc1MarketFacilitation(
    bars:List<WilliamsWaveBar>,frame:String,asOf:Long
):JSONObject {
    val result=JSONObject()
        .put("schema","TC1_CH6_MFI_FOUR_WINDOWS_PROXY_V1")
        .put("book_pdf_pages","42-45")
        .put("frame",frame).put("as_of_ms",asOf)
        .put("source","EXCHANGE_KLINE_BASE_ASSET_VOLUME_PROXY")
        .put("canonical_tick_volume_verified",false)
        .put("volume_is_price_independent_signal",false)
        .put("pattern_can_prove_w5_end",false)
        .put("execution_authority",false)
        .put("can_place_orders",false).put("can_close",false)
        .put("can_move_stop",false)
        .put("status","SOURCE_H1_OR_FRAME_UNVERIFIED")
        .put("window","UNKNOWN")
        .put("explanation_ru","Индекс облегчения рынка не рассчитан: нужна непрерывная история двух закрытых свечей и проверенный объём.")
    val span=williamsWaveFrames[frame] ?: return result
    if(asOf<=0 || bars.size<2) return result
    // Scope is only the two most recent adjacent observed exchange bars.
    // Gapped earlier history cannot be silently used to certify a full wave.
    val prev=bars[bars.lastIndex-1]
    val last=bars.last()
    if(listOf(prev,last).any { b ->
        b.t<0 || b.t%span!=0L || b.t>asOf-span ||
            listOf(b.o,b.h,b.l,b.c,b.v).any { !it.isFinite() } ||
            b.v<=0.0 || b.h<=b.l || b.l<=0.0 ||
            b.h<maxOf(b.o,b.c,b.l) || b.l>minOf(b.o,b.c)
    } || last.t-prev.t!=span || asOf-(last.t+span) !in 0 until span)
        return result
    val prior=(prev.h-prev.l)/prev.v
    val current=(last.h-last.l)/last.v
    if(!prior.isFinite() || !current.isFinite() || prior<=0 || current<=0)
        return result.put("status","MFI_NUMERICALLY_UNVERIFIED")
    val volUp=last.v>prev.v
    val volDown=last.v<prev.v
    val mfiUp=current>prior
    val mfiDown=current<prior
    val window=when {
        volUp && mfiUp -> "GREEN"
        volDown && mfiDown -> "FADE"
        volDown && mfiUp -> "FAKE"
        volUp && mfiDown -> "SQUAT"
        else -> "UNCHANGED_OR_TIED"
    }
    val ru=when(window) {
        "GREEN" -> "«Зелёный» бар: объём вырос и MFI вырос. Цена движется легче при повышении объёма; цвет НЕ задаёт направление LONG/SHORT."
        "FADE" -> "«Увядающий» бар: и объём, и MFI снизились. Интерес к движению ослаб; последующий импульс может возникнуть в любую сторону."
        "FAKE" -> "«Фальшивый» бар: объём снизился, а MFI вырос. Цена перемещается при меньшем объёме, поэтому сила движения сомнительна."
        "SQUAT" -> "«Приседающий» бар: объём вырос, а MFI снизился. Возможна борьба покупателей и продавцов; такой бар бывает и в середине тренда, это НЕ доказанный разворот."
        else -> "Объём или MFI остался на прежнем уровне. Четырёхзначная классификация TC1 здесь неоднозначна; не подменяем равенство ростом/спадом."
    }
    return result
        .put("status",if(window=="UNCHANGED_OR_TIED") "TIED_NEITHER_UP_NOR_DOWN"
            else "OBSERVED_BASE_VOLUME_PROXY")
        .put("window",window)
        .put("previous_bar_at_ms",prev.t)
        .put("latest_closed_bar_at_ms",last.t)
        .put("known_at_ms",last.t+span)
        .put("mfi_previous",prior).put("mfi_current",current)
        .put("volume_previous",prev.v).put("volume_current",last.v)
        .put("volume_direction",if(volUp) "UP" else if(volDown) "DOWN" else "TIED")
        .put("mfi_direction",if(mfiUp) "UP" else if(mfiDown) "DOWN" else "TIED")
        .put("explanation_ru","По TC1, глава 6: $ru Объём Binance — базовый объём актива, а не оригинальный тиковый объём Вильямса. Это ограниченное наблюдение без права выставлять, снимать или менять биржевые ордера.")
}
