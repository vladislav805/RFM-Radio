package com.vlad805.fmradio.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.activity.MainActivity;
import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.controller.TunerStatus;
import com.vlad805.fmradio.service.FMService;
import com.vlad805.fmradio.view.SignalStrength;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 4x1 Material 3 homescreen player widget synced with {@link FMService}.
 */
public class RadioWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "RFM-Widget";
    private static final int[] SIGNAL_BAR_IDS = {
            R.id.widget_sig_1,
            R.id.widget_sig_2,
            R.id.widget_sig_3,
            R.id.widget_sig_4,
            R.id.widget_sig_5,
    };

    private static final int PENDING_FLAGS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;

    private static volatile RadioState sLastState;
    private static volatile String sLastFavoriteTitle;
    private static volatile boolean sLastSeeking;

    @LayoutRes
    protected int getLayoutResId(@Nullable final Bundle options) {
        return R.layout.widget_radio_4x1;
    }

    protected boolean isResponsive() {
        return false;
    }

    protected static int resolveMinWidthDp(@Nullable final Bundle options) {
        if (options == null) {
            return 0;
        }
        return options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
    }

    @Override
    public void onUpdate(
            final Context context,
            final AppWidgetManager appWidgetManager,
            final int[] appWidgetIds
    ) {
        for (final int appWidgetId : appWidgetIds) {
            final Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
            appWidgetManager.updateAppWidget(appWidgetId, buildCurrentViews(context, options));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(
            final Context context,
            final AppWidgetManager appWidgetManager,
            final int appWidgetId,
            final Bundle newOptions
    ) {
        Log.d(TAG, "onAppWidgetOptionsChanged: id=" + appWidgetId
                + " minW=" + resolveMinWidthDp(newOptions)
                + " responsive=" + isResponsive());
        appWidgetManager.updateAppWidget(appWidgetId, buildCurrentViews(context, newOptions));
    }

    public static void update(
            @NonNull final Context context,
            @NonNull final RadioState state,
            @Nullable final String favoriteTitle,
            final boolean seeking
    ) {
        sLastState = state;
        sLastFavoriteTitle = favoriteTitle;
        sLastSeeking = seeking;

        final AppWidgetManager manager = AppWidgetManager.getInstance(context);
        updateFixedProvider(context, manager, RadioWidgetProvider.class, R.layout.widget_radio_4x1, state, favoriteTitle, seeking);
        updateResponsive2x1Provider(context, manager, state, favoriteTitle, seeking);
    }

    private static void updateFixedProvider(
            @NonNull final Context context,
            @NonNull final AppWidgetManager manager,
            @NonNull final Class<? extends AppWidgetProvider> providerClass,
            @LayoutRes final int layoutResId,
            @NonNull final RadioState state,
            @Nullable final String favoriteTitle,
            final boolean seeking
    ) {
        final ComponentName component = new ComponentName(context, providerClass);
        final int[] ids = manager.getAppWidgetIds(component);
        if (ids == null || ids.length == 0) {
            return;
        }
        final RemoteViews views = buildViews(context, layoutResId, state, favoriteTitle, seeking);
        manager.updateAppWidget(ids, views);
    }

    private static void updateResponsive2x1Provider(
            @NonNull final Context context,
            @NonNull final AppWidgetManager manager,
            @NonNull final RadioState state,
            @Nullable final String favoriteTitle,
            final boolean seeking
    ) {
        final ComponentName component = new ComponentName(context, RadioWidget2x1Provider.class);
        final int[] ids = manager.getAppWidgetIds(component);
        if (ids == null || ids.length == 0) {
            return;
        }
        final RadioWidget2x1Provider helper = new RadioWidget2x1Provider();
        for (final int id : ids) {
            final Bundle options = manager.getAppWidgetOptions(id);
            manager.updateAppWidget(id, helper.buildViewsForOptions(context, options, state, favoriteTitle, seeking));
        }
    }

    @NonNull
    private RemoteViews buildCurrentViews(
            @NonNull final Context context,
            @Nullable final Bundle options
    ) {
        RadioState state = sLastState;
        if (state == null) {
            state = new RadioState();
            state.applyFrequency(Storage.get(context).getLastFrequency());
        }
        return buildViewsForOptions(context, options, state, sLastFavoriteTitle, sLastSeeking);
    }

    @NonNull
    protected RemoteViews buildViewsForOptions(
            @NonNull final Context context,
            @Nullable final Bundle options,
            @NonNull final RadioState state,
            @Nullable final String favoriteTitle,
            final boolean seeking
    ) {
        if (isResponsive() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            final Map<SizeF, RemoteViews> sizedViews = new HashMap<>();
            sizedViews.put(new SizeF(110f, 40f), buildViews(context, R.layout.widget_radio_2x1, state, favoriteTitle, seeking));
            sizedViews.put(new SizeF(250f, 40f), buildViews(context, R.layout.widget_radio_4x1, state, favoriteTitle, seeking));
            return new RemoteViews(sizedViews);
        }
        return buildViews(context, getLayoutResId(options), state, favoriteTitle, seeking);
    }

    @NonNull
    private static RemoteViews buildViews(
            @NonNull final Context context,
            @LayoutRes final int layoutResId,
            @NonNull final RadioState state,
            @Nullable final String favoriteTitle,
            final boolean seeking
    ) {
        final RemoteViews views = new RemoteViews(context.getPackageName(), layoutResId);
        final TunerStatus status = state.getStatus();
        final boolean enabled = status == TunerStatus.ENABLED;
        final boolean transitioning = status == TunerStatus.INSTALLING
                || status == TunerStatus.INSTALLED
                || status == TunerStatus.LAUNCHING
                || status == TunerStatus.LAUNCHED
                || status == TunerStatus.ENABLING
                || status == TunerStatus.DISABLING;
        final boolean active = enabled || transitioning;
        final boolean busy = transitioning || seeking;

        final int frequency = state.getFrequency() > 0
                ? state.getFrequency()
                : Storage.get(context).getLastFrequency();
        final int precision = Storage.get(context).getSpacing() == 0 ? 2 : 1;
        views.setTextViewText(R.id.widget_frequency, Utils.getMHz(frequency, precision).trim());

        // Station subtitle: PS -> favorite title -> app name
        final String subtitle;
        if (enabled && state.getPs() != null && !state.getPs().trim().isEmpty()) {
            subtitle = state.getPs().trim();
        } else if (favoriteTitle != null && !favoriteTitle.trim().isEmpty()) {
            subtitle = favoriteTitle.trim();
        } else {
            subtitle = context.getString(R.string.app_name);
        }
        views.setTextViewText(R.id.widget_station, subtitle);

        // Signal bars (0..5)
        final int level = enabled
                ? SignalStrength.levelForMetrics(state.getRmssi(), state.getSinr())
                : 0;
        for (int i = 0; i < SIGNAL_BAR_IDS.length; i++) {
            views.setImageViewResource(
                    SIGNAL_BAR_IDS[i],
                    i < level ? R.drawable.bg_widget_signal_on : R.drawable.bg_widget_signal_off
            );
        }

        // Power button active/idle styling + spinner while starting/stopping/seeking
        views.setInt(
                R.id.widget_btn_power,
                "setBackgroundResource",
                active ? R.drawable.bg_widget_btn_power_on : R.drawable.bg_widget_btn_tonal
        );
        if (busy) {
            views.setImageViewResource(R.id.widget_btn_power, android.R.color.transparent);
            views.setViewVisibility(R.id.widget_power_progress, View.VISIBLE);
        } else {
            views.setImageViewResource(R.id.widget_btn_power, R.drawable.ic_rfm_power);
            views.setInt(
                    R.id.widget_btn_power,
                    "setColorFilter",
                    ContextCompat.getColor(
                            context,
                            active ? R.color.widget_btn_power_on_icon : R.color.widget_text_primary
                    )
            );
            views.setViewVisibility(R.id.widget_power_progress, View.GONE);
        }

        // REC pill & seek buttons (present in 4x1 layout)
        if (layoutResId == R.layout.widget_radio_4x1) {
            views.setViewVisibility(R.id.widget_rec_pill, View.VISIBLE);
            if (enabled) {
                final boolean recording = state.isRecording();
                final int recColor = ContextCompat.getColor(context, R.color.widget_rec_text);
                views.setInt(
                        R.id.widget_rec_pill,
                        "setBackgroundResource",
                        recording ? R.drawable.bg_widget_rec_pill_active : R.drawable.bg_widget_rec_pill
                );
                views.setInt(R.id.widget_rec_dot, "setColorFilter", recColor);
                views.setTextColor(R.id.widget_rec_text, recColor);
                views.setTextViewText(
                        R.id.widget_rec_text,
                        recording ? formatDuration(state.getRecordingDuration()) : context.getString(R.string.widget_rec_short)
                );
                final Intent recIntent = new Intent(context, FMService.class)
                        .setAction(recording ? FMService.ACTION_RECORD_STOP : FMService.ACTION_RECORD_START);
                views.setOnClickPendingIntent(
                        R.id.widget_rec_pill,
                        PendingIntent.getService(context, 404, recIntent, PENDING_FLAGS)
                );
            } else {
                final int disabledColor = ContextCompat.getColor(context, R.color.widget_rec_disabled_text);
                views.setInt(
                        R.id.widget_rec_pill,
                        "setBackgroundResource",
                        R.drawable.bg_widget_rec_pill_disabled
                );
                views.setInt(R.id.widget_rec_dot, "setColorFilter", disabledColor);
                views.setTextColor(R.id.widget_rec_text, disabledColor);
                views.setTextViewText(R.id.widget_rec_text, context.getString(R.string.widget_rec_short));
                views.setOnClickPendingIntent(R.id.widget_rec_pill, null);
            }
        }

        // Open app on info area click
        final Intent openApp = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        views.setOnClickPendingIntent(
                R.id.widget_info_area,
                PendingIntent.getActivity(context, 400, openApp, PENDING_FLAGS)
        );

        // Power toggle
        final Intent toggleIntent = new Intent(context, FMService.class).setAction(FMService.ACTION_TOGGLE);
        views.setOnClickPendingIntent(
                R.id.widget_btn_power,
                PendingIntent.getService(context, 401, toggleIntent, PENDING_FLAGS)
        );

        // Seek prev / next (present in 4x1 layout)
        if (layoutResId == R.layout.widget_radio_4x1) {
            final Intent prevIntent = new Intent(context, FMService.class)
                    .setAction(FMService.ACTION_NOTIFICATION_SEEK)
                    .putExtra(FMService.EXTRA_SEEK_DIRECTION, -1);
            views.setOnClickPendingIntent(
                    R.id.widget_btn_prev,
                    PendingIntent.getService(context, 402, prevIntent, PENDING_FLAGS)
            );

            final Intent nextIntent = new Intent(context, FMService.class)
                    .setAction(FMService.ACTION_NOTIFICATION_SEEK)
                    .putExtra(FMService.EXTRA_SEEK_DIRECTION, 1);
            views.setOnClickPendingIntent(
                    R.id.widget_btn_next,
                    PendingIntent.getService(context, 403, nextIntent, PENDING_FLAGS)
            );
        }

        return views;
    }

    private static String formatDuration(final long totalSeconds) {
        final long safe = Math.max(0L, totalSeconds);
        final long minutes = safe / 60L;
        final long seconds = safe % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
