package com.vlad805.fmradio.helper;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import androidx.annotation.StringRes;
import com.vlad805.fmradio.R;

/**
 * vlad805 (c) 2019
 */
public class EditTextDialog extends AlertDialog.Builder {

	public interface OnEditCompleted {
		void onReady(String title);
	}

	private EditText mEditText;

	public EditTextDialog(Context context, String defaultValue, OnEditCompleted listener) {
		super(context);

		if (defaultValue == null) {
			defaultValue = "";
		}

		final LayoutInflater factory = LayoutInflater.from(context);

		final View textEntryView = factory.inflate(R.layout.layout_dialog_edit_text, null);

		mEditText = textEntryView.findViewById(R.id.dialog_edit_text_text);

		/*et.setFilters(new InputFilter[] {new InputFilter.LengthFilter(C.PRESET_NAME_MAX_LENGTH)});
		et.setText(defaultValue);
		et.setSelection(defaultValue.length(), defaultValue.length());*/

		mEditText.setText(defaultValue);

		setView(textEntryView);

		setPositiveButton(android.R.string.ok, (dialog, which) -> {
			if (listener != null) {
				listener.onReady(mEditText.getText().toString().trim());
			}
		});

		setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel());
	}

	public final EditTextDialog setTitle(@StringRes int resId) {
		super.setTitle(resId);
		return this;
	}

	public final EditTextDialog setInputType(int type) {
		mEditText.setInputType(type);
		return this;
	}

	public final EditTextDialog setHint(String hint) {
		mEditText.setHint(hint);
		return this;
	}

	public final EditTextDialog setHint(@StringRes int hint) {
		mEditText.setHint(hint);
		return this;
	}

	public final EditTextDialog setOnKeyListener(final TextWatcher listener) {
		mEditText.addTextChangedListener(listener);
		return this;
	}

	public EditText getView() {
		return mEditText;
	}

	public final void open() {
		create().show();
	}
}
