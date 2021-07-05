package com.vlad805.fmradio.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import com.vlad805.fmradio.R;

/**
 * Copied from https://github.com/Dean1990/ReflectTextView
 */
@SuppressWarnings("deprecation")
@SuppressLint("AppCompatCustomView")
public class TextViewWithReflection extends TextView {
	private final Paint mPaintEmpty = new Paint();
	private final Matrix mMatrix;

	private Paint mPaint;

	// The height multiple of the reflection
	private float mReflectHeightMultiple = 0.5f;

	// Y-axis offset, because the reflection height multiple is set to less than 1,
	// there will be offset, showing part of the reflection
	private float mOffsetY;

	public TextViewWithReflection(final Context context, final AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public TextViewWithReflection(final Context context, final AttributeSet attrs, final int defStyleAttr) {
		super(context, attrs);

		mPaintEmpty.setAlpha(128);

		final TypedArray a = context
				.getTheme()
				.obtainStyledAttributes(attrs, R.styleable.reflect, defStyleAttr, 0);

		final int n = a.getIndexCount();

		for (int i = 0; i < n; ++i) {
			final int attr = a.getIndex(i);

			if (attr == R.styleable.reflect_reflectHeightMultiple) {// The height multiple of the reflection [0-1]
				mReflectHeightMultiple = a.getFloat(attr, 1f);

				if (mReflectHeightMultiple < 0) {
					mReflectHeightMultiple = 0;
				} else if (mReflectHeightMultiple > 1) {
					mReflectHeightMultiple = 1;
				}
			}
		}

		a.recycle();

		mMatrix = new Matrix();
		mMatrix.preScale(1, -1);

		setLayerType(View.LAYER_TYPE_SOFTWARE, null);
	}

	@SuppressLint("DrawAllocation")
	@Override
	protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
	{
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);

		final int temp = (int) (getMeasuredHeight() - (getLineHeight() - getTextSize()) / 2);
		mOffsetY = temp - temp * mReflectHeightMultiple;
		setMeasuredDimension(
				getMeasuredWidth(),
				Math.round(temp * 2 - mOffsetY)
		);
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

		@SuppressLint("DrawAllocation")
		final Bitmap reflectionImage = Bitmap.createBitmap(
				originalImage,
				0,
				0,
				Math.min(width, originalImage.getWidth()),
				height,
				mMatrix,
				false
		);

		// Draw reflection
		canvas.drawBitmap(reflectionImage, 0, mOffsetY, mPaintEmpty);

		if (mPaint == null) {
			mPaint = new Paint();

			// The effect of the shadow can be set according to your needs
			final LinearGradient shader = new LinearGradient(
			        0, (height + mOffsetY) / 2,
                    0, height,
                    Color.BLACK,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
            );
			mPaint.setShader(shader);
			mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
		}

		canvas.drawRect(0, (height + mOffsetY) / 2, width, height, mPaint);
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
