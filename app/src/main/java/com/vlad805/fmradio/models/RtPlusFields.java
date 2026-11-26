package com.vlad805.fmradio.models;

import android.content.res.Resources;

import com.vlad805.fmradio.R;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Localized labels for the RT+ wire keys, in content type order. */
public final class RtPlusFields {
    private static final Map<String, Integer> LABELS = buildLabelResources();

    private RtPlusFields() {}

    public static Set<String> names() {
        return LABELS.keySet();
    }

    public static Map<String, String> localizedLabels(final Resources resources) {
        final Map<String, String> labels = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> field : LABELS.entrySet()) {
            labels.put(field.getKey(), resources.getString(field.getValue()));
        }

        return labels;
    }

    private static Map<String, Integer> buildLabelResources() {
        final Map<String, Integer> labels = new LinkedHashMap<>();

        labels.put("ITEM.TITLE", R.string.rt_plus_item_title);
        labels.put("ITEM.ALBUM", R.string.rt_plus_item_album);
        labels.put("ITEM.TRACKNUMBER", R.string.rt_plus_item_tracknumber);
        labels.put("ITEM.ARTIST", R.string.rt_plus_item_artist);
        labels.put("ITEM.COMPOSITION", R.string.rt_plus_item_composition);
        labels.put("ITEM.MOVEMENT", R.string.rt_plus_item_movement);
        labels.put("ITEM.CONDUCTOR", R.string.rt_plus_item_conductor);
        labels.put("ITEM.COMPOSER", R.string.rt_plus_item_composer);
        labels.put("ITEM.BAND", R.string.rt_plus_item_band);
        labels.put("ITEM.COMMENT", R.string.rt_plus_item_comment);
        labels.put("ITEM.GENRE", R.string.rt_plus_item_genre);
        labels.put("INFO.NEWS", R.string.rt_plus_info_news);
        labels.put("INFO.NEWS.LOCAL", R.string.rt_plus_info_news_local);
        labels.put("INFO.STOCKMARKET", R.string.rt_plus_info_stockmarket);
        labels.put("INFO.SPORT", R.string.rt_plus_info_sport);
        labels.put("INFO.LOTTERY", R.string.rt_plus_info_lottery);
        labels.put("INFO.HOROSCOPE", R.string.rt_plus_info_horoscope);
        labels.put("INFO.DAILY_DIVERSION", R.string.rt_plus_info_daily_diversion);
        labels.put("INFO.HEALTH", R.string.rt_plus_info_health);
        labels.put("INFO.EVENT", R.string.rt_plus_info_event);
        labels.put("INFO.SCENE", R.string.rt_plus_info_scene);
        labels.put("INFO.CINEMA", R.string.rt_plus_info_cinema);
        labels.put("INFO.TV", R.string.rt_plus_info_tv);
        labels.put("INFO.DATE_TIME", R.string.rt_plus_info_date_time);
        labels.put("INFO.WEATHER", R.string.rt_plus_info_weather);
        labels.put("INFO.TRAFFIC", R.string.rt_plus_info_traffic);
        labels.put("INFO.ALARM", R.string.rt_plus_info_alarm);
        labels.put("INFO.ADVERTISEMENT", R.string.rt_plus_info_advertisement);
        labels.put("INFO.URL", R.string.rt_plus_info_url);
        labels.put("INFO.OTHER", R.string.rt_plus_info_other);
        labels.put("STATIONNAME.SHORT", R.string.rt_plus_stationname_short);
        labels.put("STATIONNAME.LONG", R.string.rt_plus_stationname_long);
        labels.put("PROGRAMME.NOW", R.string.rt_plus_programme_now);
        labels.put("PROGRAMME.NEXT", R.string.rt_plus_programme_next);
        labels.put("PROGRAMME.PART", R.string.rt_plus_programme_part);
        labels.put("PROGRAMME.HOST", R.string.rt_plus_programme_host);
        labels.put("PROGRAMME.EDITORIAL_STAFF", R.string.rt_plus_programme_editorial_staff);
        labels.put("PROGRAMME.FREQUENCY", R.string.rt_plus_programme_frequency);
        labels.put("PROGRAMME.HOMEPAGE", R.string.rt_plus_programme_homepage);
        labels.put("PROGRAMME.SUBCHANNEL", R.string.rt_plus_programme_subchannel);
        labels.put("PHONE.HOTLINE", R.string.rt_plus_phone_hotline);
        labels.put("PHONE.STUDIO", R.string.rt_plus_phone_studio);
        labels.put("PHONE.OTHER", R.string.rt_plus_phone_other);
        labels.put("SMS.STUDIO", R.string.rt_plus_sms_studio);
        labels.put("SMS.OTHER", R.string.rt_plus_sms_other);
        labels.put("EMAIL.HOTLINE", R.string.rt_plus_email_hotline);
        labels.put("EMAIL.STUDIO", R.string.rt_plus_email_studio);
        labels.put("EMAIL.OTHER", R.string.rt_plus_email_other);
        labels.put("MMS.OTHER", R.string.rt_plus_mms_other);
        labels.put("CHAT", R.string.rt_plus_chat);
        labels.put("CHAT.CENTER", R.string.rt_plus_chat_center);
        labels.put("VOTE.QUESTION", R.string.rt_plus_vote_question);
        labels.put("VOTE.CENTRE", R.string.rt_plus_vote_centre);
        labels.put("PRIVATE.56", R.string.rt_plus_private_56);
        labels.put("PRIVATE.57", R.string.rt_plus_private_57);
        labels.put("PRIVATE.58", R.string.rt_plus_private_58);
        labels.put("DESCRIPTOR.PLACE", R.string.rt_plus_descriptor_place);
        labels.put("DESCRIPTOR.APPOINTMENT", R.string.rt_plus_descriptor_appointment);
        labels.put("DESCRIPTOR.IDENTIFIER", R.string.rt_plus_descriptor_identifier);
        labels.put("DESCRIPTOR.PURCHASE", R.string.rt_plus_descriptor_purchase);
        labels.put("DESCRIPTOR.GET_DATA", R.string.rt_plus_descriptor_get_data);

        return Collections.unmodifiableMap(labels);
    }
}
