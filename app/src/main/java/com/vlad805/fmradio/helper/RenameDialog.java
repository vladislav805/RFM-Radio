package com.vlad805.fmradio.helper;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import androidx.annotation.StringRes;
import com.vlad805.fmradio.R;

/**
 * vlad805 (c) 2019
 */
public class RenameDialog extends AlertDialog.Builder {

	public interface OnTitleReady {
		void onReady(String title);
	}

	public RenameDialog(Context context, String defaultValue, OnTitleReady listener) {
		super(context);

		if (defaultValue == null) {
			defaultValue = "";
		}

		final LayoutInflater factory = LayoutInflater.from(context);

		final View textEntryView = factory.inflate(R.layout.dialog_rename, null);

		final EditText et = textEntryView.findViewById(R.id.dialog_rename_title);

		/*et.setFilters(new InputFilter[] {new InputFilter.LengthFilter(C.PRESET_NAME_MAX_LENGTH)});
		et.setText(defaultValue);
		et.setSelection(defaultValue.length(), defaultValue.length());*/

		/*et.setInputType(InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
		et.setGravity(Gravity.CENTER_HORIZONTAL);*/

		et.setText(defaultValue);

		setView(textEntryView);

		setPositiveButton(android.R.string.ok, (dialog, which) -> {
			if (listener != null) {
				listener.onReady(et.getText().toString().trim());
			}
		});

		setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel());
	}

	public final RenameDialog setTitle(@StringRes int resId) {
		super.setTitle(resId);
		return this;
	}

	public final void open() {
		create().show();
	}
}
