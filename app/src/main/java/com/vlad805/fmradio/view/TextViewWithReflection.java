package com.vlad805.fmradio.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import com.vlad805.fmradio.R;

@SuppressLint("AppCompatCustomView")
public class TextViewWithReflection extends TextView {
	private final Paint mPaintEmpty = new Paint();
	private final Matrix mMatrix;

	private Paint mPaint;

	// The height multiple of the reflection
	private float mReflectHeightMultiple = 0.5f;

	private int mReflectPadding = 0;

	public TextViewWithReflection(final Context context, final AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public TextViewWithReflection(final Context context, final AttributeSet attrs, final int defStyleAttr) {
		super(context, attrs);

		mPaintEmpty.setAlpha(128);

		try (final TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.TextViewWithReflection, defStyleAttr, 0)) {
			final int n = a.getIndexCount();

			for (int i = 0; i < n; ++i) {
				final int attr = a.getIndex(i);

				if (attr == R.styleable.TextViewWithReflection_reflectionHeightMultiple) {// The height multiple of the reflection [0-1]
					final float value = a.getFloat(attr, mReflectHeightMultiple);
					mReflectHeightMultiple = Math.min(1, Math.max(0, value));
				} else if (attr == R.styleable.TextViewWithReflection_reflectionPadding) {
					mReflectPadding = a.getInt(attr, mReflectPadding);
				}
			}
		}

		mMatrix = new Matrix();
		mMatrix.preScale(1, -1);

		setLayerType(View.LAYER_TYPE_SOFTWARE, null);
	}

	/**
	 *
	 * @param canvas Canvas on which to paint
	 */
	@Override
	protected void onDraw(final Canvas canvas) {
		super.onDraw(canvas);

		// Get size
		final int height = getMeasuredHeight();
		final int width = getMeasuredWidth();

		// Enable cache
		setDrawingCacheEnabled(true);

		final Bitmap originalImage = getDrawingCache();

		final int baseline = getBaseline();

		@SuppressLint("DrawAllocation")
		final Bitmap reflectionImage = Bitmap.createBitmap(
				originalImage,
				0,
				0,
				Math.min(width, originalImage.getWidth()),
				baseline,
				mMatrix,
				false
		);

		// Draw reflection
		canvas.drawBitmap(reflectionImage, 0, baseline + mReflectPadding, mPaintEmpty);

		if (mPaint == null) {
			mPaint = new Paint();

			// The effect of the shadow can be set according to your needs
			final LinearGradient shader = new LinearGradient(
			        0, (height * mReflectHeightMultiple + baseline) / 2,
                    0, height,
                    Color.BLACK,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
            );
			mPaint.setShader(shader);
			mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
		}

		canvas.drawRect(0, (height * mReflectHeightMultiple + baseline) / 2, width, height, mPaint);
	}

	@Override
	protected void onTextChanged(final CharSequence text, final int start, final int lengthBefore, final int lengthAfter) {
		super.onTextChanged(text, start, lengthBefore, lengthAfter);

		buildDrawingCache();
		postInvalidate();
		// Every time the TextView is updated, the last image remains, so the
		// DrawingCache is clear after each refresh of the TextView.
		destroyDrawingCache();
	}
}
