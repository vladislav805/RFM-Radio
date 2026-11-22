package com.vlad805.fmradio.helper;

import android.content.Context;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import androidx.annotation.StringRes;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.vlad805.fmradio.R;

/**
 * vlad805 (c) 2019
 */
public class EditTextDialog extends MaterialAlertDialogBuilder {

	public interface OnEditCompleted {
		void onReady(String title);
	}

	private final TextInputLayout mInputLayout;
	private final EditText mEditText;

	public EditTextDialog(Context context, String defaultValue, OnEditCompleted listener) {
		super(context);

		if (defaultValue == null) {
			defaultValue = "";
		}

		final LayoutInflater factory = LayoutInflater.from(context);

		final View textEntryView = factory.inflate(R.layout.layout_dialog_edit_text, null);

		mInputLayout = textEntryView.findViewById(R.id.dialog_edit_text_layout);
		mEditText = textEntryView.findViewById(R.id.dialog_edit_text_text);

		mEditText.setText(defaultValue);
		mEditText.setSelection(defaultValue.length());

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
		if (mInputLayout != null) {
			mInputLayout.setHint(hint);
		} else {
			mEditText.setHint(hint);
		}
		return this;
	}

	public final EditTextDialog setHint(@StringRes int hint) {
		if (mInputLayout != null) {
			mInputLayout.setHint(hint);
		} else {
			mEditText.setHint(hint);
		}
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
