package com.vlad805.fmradio.fragments;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.SwitchPreferenceCompat;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.RFMApplication;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.AppTheme;
import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.helper.TunerDriverDetector;
import com.vlad805.fmradio.preferences.BandUtils;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;

import static com.vlad805.fmradio.Utils.fetch;

/**
 * vlad805 (c) 2020
 */
public class PreferencesFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceClickListener {
	/** Permission request used when enabling continuous FM capture. */
	private static final int REQUEST_RECORD_AUDIO_FOR_PRE_ROLL = 1001;
	private static final String KEY_INFO_VERSION = "pref_info_version";

	private ProgressDialog mProgress;

	@Override
	public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
		final Context context = requireContext();
		final Storage storage = Storage.get(context);
		final TunerDriver driver = TunerDriverDetector.getTunerDriver(context);

		if (
				driver.hasKnownCapabilities() &&
				!driver.supportsJapanWide() &&
				storage.getRegion() == BandUtils.BAND_JAPAN_WIDE
		) {
			storage.setRegion(BandUtils.BAND_JAPAN_STANDARD);
		}

		setPreferencesFromResource(R.xml.preferences, rootKey);

		final int iconTint = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0);
		tintPreferenceIcons(getPreferenceScreen(), iconTint);

		configureBackendCapabilities(driver);
		configureInteractivePreferences(driver);
	}

	@Override
	public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		setDivider(null);
		setDividerHeight(0);

		final RecyclerView listView = getListView();
		final float density = getResources().getDisplayMetrics().density;
		final int bottomPad = Math.round(16f * density);
		listView.setPadding(listView.getPaddingLeft(), listView.getPaddingTop(), listView.getPaddingRight(), bottomPad);
		listView.setClipToPadding(false);

		if (savedInstanceState != null) {
			return;
		}

		final float offset = 14f * density;
		listView.post(() -> {
			if (!isAdded()) {
				return;
			}
			final int count = listView.getChildCount();
			for (int i = 0; i < count; i++) {
				final View child = listView.getChildAt(i);
				child.setAlpha(0f);
				child.setTranslationY(offset);
				child.animate()
						.alpha(1f)
						.translationY(0f)
						.setStartDelay(Math.min(i * 22L, 200L))
						.setDuration(220L)
						.setInterpolator(new FastOutSlowInInterpolator())
						.start();
			}
		});
	}

	@Override
	public void onDisplayPreferenceDialog(@NonNull final Preference preference) {
		if (preference instanceof ListPreference) {
			final ListPreference listPref = (ListPreference) preference;
			final CharSequence[] entries = listPref.getEntries();
			final CharSequence[] entryValues = listPref.getEntryValues();
			final int checkedIndex = listPref.findIndexOfValue(listPref.getValue());

			new MaterialAlertDialogBuilder(requireContext())
					.setTitle(listPref.getDialogTitle() != null ? listPref.getDialogTitle() : listPref.getTitle())
					.setSingleChoiceItems(entries, checkedIndex, (dialog, which) -> {
						if (which >= 0 && entryValues != null && which < entryValues.length) {
							final String value = entryValues[which].toString();
							if (listPref.callChangeListener(value)) {
								listPref.setValue(value);
							}
						}
						dialog.dismiss();
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
			return;
		}

		if (preference instanceof EditTextPreference) {
			final EditTextPreference editPref = (EditTextPreference) preference;
			final View content = LayoutInflater.from(requireContext()).inflate(R.layout.pref_dialog_edittext, null);
			final TextView messageView = content.findViewById(R.id.pref_dialog_message);
			final TextInputEditText editText = content.findViewById(R.id.pref_dialog_edit);

			if (editPref.getDialogMessage() != null) {
				messageView.setText(editPref.getDialogMessage());
				messageView.setVisibility(View.VISIBLE);
			}

			final int inputType = Storage.Key.TUNER_ANTENNA.equals(editPref.getKey())
					? InputType.TYPE_CLASS_NUMBER
					: InputType.TYPE_CLASS_TEXT;
			editText.setInputType(inputType);
			if (editPref.getText() != null) {
				editText.setText(editPref.getText());
				editText.setSelection(editPref.getText().length());
			}

			new MaterialAlertDialogBuilder(requireContext())
					.setTitle(editPref.getDialogTitle() != null ? editPref.getDialogTitle() : editPref.getTitle())
					.setView(content)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok, (dialog, which) -> {
						final String value = editText.getText() != null ? editText.getText().toString() : "";
						if (editPref.callChangeListener(value)) {
							editPref.setText(value);
						}
					})
					.show();
			return;
		}

		super.onDisplayPreferenceDialog(preference);
	}

	private void tintPreferenceIcons(@Nullable final PreferenceGroup group, final int tint) {
		if (group == null) {
			return;
		}
		final int count = group.getPreferenceCount();
		for (int i = 0; i < count; i++) {
			final Preference pref = group.getPreference(i);
			final Drawable icon = pref.getIcon();
			if (icon != null) {
				final Drawable wrapped = DrawableCompat.wrap(icon.mutate());
				DrawableCompat.setTint(wrapped, tint);
				pref.setIcon(wrapped);
			}
			if (pref instanceof PreferenceGroup) {
				tintPreferenceIcons((PreferenceGroup) pref, tint);
			}
		}
	}

	private void configureBackendCapabilities(final TunerDriver driver) {
		if (!driver.hasKnownCapabilities()) {
			return;
		}

		if (!driver.supportsJapanWide()) {
			final ListPreference region = findPreference(Storage.Key.TUNER_REGION);
			if (region != null) {
				region.setEntries(R.array.pref_tuner_region_entries_narrow_japan);
				region.setEntryValues(R.array.pref_tuner_region_values_narrow_japan);
				if (String.valueOf(BandUtils.BAND_JAPAN_WIDE).equals(region.getValue())) {
					region.setValue(String.valueOf(BandUtils.BAND_JAPAN_STANDARD));
				}
			}
		}

		if (!driver.supportsSoftMute()) {
			disableUnsupportedPreference(findPreference(Storage.Key.TUNER_SOFT_MUTE));
		}

		if (!driver.supportsNotchFilter()) {
			disableUnsupportedPreference(findPreference(Storage.Key.TUNER_NOTCH_FILTER));
		}

		if (!driver.supportsAntenna()) {
			disableUnsupportedPreference(findPreference(Storage.Key.TUNER_ANTENNA));
		}

		if (!driver.supportsAutoAf()) {
			disableUnsupportedPreference(findPreference(Storage.Key.RDS_AUTO_AF));
		}
	}

	private void configureInteractivePreferences(final TunerDriver driver) {
		if (!driver.hasKnownCapabilities() || driver.supportsAntenna()) {
			final EditTextPreference antenna = findPreference(Storage.Key.TUNER_ANTENNA);
			if (antenna != null) {
				antenna.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
				antenna.setOnPreferenceChangeListener((preference, newValue) -> {
					final String raw = newValue instanceof String ? (String) newValue : null;
					if (!Storage.isValidNonNegativeInt(raw)) {
						Toast.makeText(requireContext(), R.string.pref_tuner_antenna_error, Toast.LENGTH_SHORT).show();
						return false;
					}
					return true;
				});
			}
		}

		for (final String key : new String[]{Storage.Key.RECORDING_DIRECTORY, Storage.Key.RECORDING_FILENAME}) {
			final EditTextPreference textPref = findPreference(key);
			if (textPref != null) {
				textPref.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_TEXT));
			}
		}

		final SwitchPreferenceCompat savePast = findPreference(Storage.Key.RECORDING_SAVE_PAST);
		if (savePast != null) {
			savePast.setOnPreferenceChangeListener((preference, newValue) -> {
				if (!(boolean) newValue) {
					return true;
				}

				new MaterialAlertDialogBuilder(requireContext())
						.setTitle(R.string.pref_recording_save_past_warning_title)
						.setMessage(R.string.pref_recording_save_past_warning_message)
						.setNegativeButton(android.R.string.cancel, null)
						.setPositiveButton(android.R.string.ok, (dialog, which) -> enablePreRoll(savePast))
						.show();
				return false;
			});
		}

		final Preference theme = findPreference(Storage.Key.APP_THEME);
		if (theme != null) {
			theme.setOnPreferenceChangeListener((preference, newValue) -> {
				final String raw = newValue instanceof String ? (String) newValue : null;
				RFMApplication.applyTheme(AppTheme.fromPreferenceValue(raw));
				return true;
			});
		}

		final Preference version = findPreference(KEY_INFO_VERSION);
		if (version != null) {
			version.setSummary(String.format(
					Locale.ENGLISH,
					"v%s (build %d / %s)",
					BuildConfig.VERSION_NAME,
					BuildConfig.VERSION_CODE,
					BuildConfig.BUILD_TYPE
			));
			version.setOnPreferenceClickListener(this);
		}
	}

	private void disableUnsupportedPreference(@Nullable final Preference preference) {
		if (preference == null) {
			return;
		}
		preference.setEnabled(false);
		if (preference instanceof TwoStatePreference) {
			final TwoStatePreference twoState = (TwoStatePreference) preference;
			twoState.setSummaryOn(R.string.pref_not_supported_by_device);
			twoState.setSummaryOff(R.string.pref_not_supported_by_device);
		} else {
			preference.setSummaryProvider(null);
			preference.setSummary(R.string.pref_not_supported_by_device);
		}
	}

	/** Requests microphone access before enabling persistent pre-roll capture. */
	private void enablePreRoll(final SwitchPreferenceCompat preference) {
		if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
			setPreRollEnabled(preference);
			return;
		}

		requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_FOR_PRE_ROLL);
	}

	/** Persists the enabled preference and synchronizes the visible switch. */
	private void setPreRollEnabled(final SwitchPreferenceCompat preference) {
		preference.setChecked(true);
	}

	@Override
	public void onRequestPermissionsResult(
			final int requestCode,
			@NonNull final String[] permissions,
			@NonNull final int[] grantResults
	) {
		if (requestCode == REQUEST_RECORD_AUDIO_FOR_PRE_ROLL) {
			final SwitchPreferenceCompat preference = findPreference(Storage.Key.RECORDING_SAVE_PAST);
			if (
					grantResults.length > 0 &&
					grantResults[0] == PackageManager.PERMISSION_GRANTED &&
					preference != null
			) {
				setPreRollEnabled(preference);
			} else {
				Toast.makeText(requireContext(), R.string.record_permissions_required, Toast.LENGTH_LONG).show();
			}
			return;
		}

		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
	}

	@Override
	public boolean onPreferenceClick(@NonNull final Preference preference) {
		if (KEY_INFO_VERSION.equals(preference.getKey())) {
			checkVersion();
			return true;
		}
		return false;
	}

	private void checkVersion() {
		final Context ctx = getContext();
		mProgress = ProgressDialog.create(ctx).text(R.string.pref_version_check_progress).show();
		fetch("https://rfm.velu.ga/app_version.json", new Utils.FetchCallback() {
			@Override
			public void onSuccess(final JSONObject json) {
				mProgress.hide();

				final JSONObject latest = json.optJSONObject("latest");

				if (latest == null) {
					Toast.makeText(ctx, "err", Toast.LENGTH_SHORT).show();
					return;
				}

				final boolean isFresh = latest.optInt("build") <= BuildConfig.VERSION_CODE;

				final String title;
				final String content;

				if (isFresh) {
					title = PreferencesFragment.this.getString(R.string.pref_version_check_fresh_title);
					content = PreferencesFragment.this.getString(R.string.pref_version_check_fresh_content);
				} else {
					title = PreferencesFragment.this.getString(R.string.pref_version_check_need_update_title);
					content = PreferencesFragment.this.getString(
							R.string.pref_version_check_need_update_content,
							latest.optString("version"),
							latest.optInt("build"),
							BuildConfig.VERSION_NAME,
							BuildConfig.VERSION_CODE,
							latest.optString("changelog", "< not specified >")
					);
				}

				final MaterialAlertDialogBuilder ab = new MaterialAlertDialogBuilder(Objects.requireNonNull(ctx));
				ab.setTitle(title).setMessage(content);

				if (!isFresh) {
					ab.setPositiveButton(R.string.pref_version_check_need_update_ok, (dialog, which) -> {
						final Intent intent = new Intent(Intent.ACTION_VIEW);
						intent.setData(Uri.parse(latest.optString("url")));
						PreferencesFragment.this.startActivity(intent);
					});
				}

				ab.setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel());

				ab.create().show();
			}

			@Override
			public void onError(final Throwable exception) {
				mProgress.hide();

				Toast.makeText(getContext(), getString(R.string.pref_version_check_error, exception.getMessage()), Toast.LENGTH_LONG).show();
			}
		});
	}
}
