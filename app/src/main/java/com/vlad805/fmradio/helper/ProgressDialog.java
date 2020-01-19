package com.vlad805.fmradio.helper;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.StringRes;
import com.vlad805.fmradio.R;

/**
 * vlad805 (c) 2020
 */
public class ProgressDialog {
	public static ProgressDialog create(Context context) {
		return new ProgressDialog(context);
	}

	private final AlertDialog.Builder builder;
	private AlertDialog dialog;
	private View root;
	private TextView text;

	private ProgressDialog(Context context) {
		builder = new AlertDialog.Builder(context);
		builder.setCancelable(false);
		root = LayoutInflater.from(context).inflate(R.layout.layout_dialog_loading, null);
		text = root.findViewById(R.id.progress_dialog_text);
		builder.setView(root);
	}

	public ProgressDialog text(@StringRes int id) {
		text.setText(id);
		return this;
	}

	public ProgressDialog text(String content) {
		text.setText(content);
		return this;
	}

	public ProgressDialog show() {
		dialog = builder.create();
		dialog.show();
		return this;
	}

	public void close() {
		if (dialog != null) {
			dialog.dismiss();
		}
	}
}
