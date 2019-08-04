package com.vlad805.fmradio.view;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * vlad805 (c) 2019
 */
public class TextViewWithSupportReflection extends TextView {

	private Shader mTextGradient;

	public TextViewWithSupportReflection(Context context) {
		super(context);
		init();
	}

	public TextViewWithSupportReflection(Context context, AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	private void init() {

	}

	/*@Override
	protected void onDraw(Canvas canvas) {
		canvas.translate(0, getHeight() + 30);
		canvas.scale(1, -0.8f);


		super.onDraw(canvas);
	}*/

	/*@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);

		if (changed) {
			if (mTextGradient == null) {
				mTextGradient = new LinearGradient(0, 0, 0, getHeight() * 2, Color.TRANSPARENT, Color.LTGRAY, Shader.TileMode.CLAMP);
			}
			getPaint().setShader(mTextGradient);
		}
	}*/

	public Bitmap getReflection(float angle) {
		Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
		mTextGradient = new LinearGradient(0, 0, 0, getHeight(), Color.TRANSPARENT, Color.WHITE, Shader.TileMode.CLAMP);
		Canvas canvas = new Canvas(bitmap);
		getPaint().setShader(mTextGradient);

		canvas.save();
		canvas.translate(0, getHeight() * angle);
		canvas.scale(1f, -angle);
		draw(canvas);
		canvas.restore();

		getPaint().setShader(null);

		return bitmap;
	}
}
