package com.xinyv.median;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * Word-mark for the Tailnet entry page, mirroring the main browser's "median" logo:
 * dark text with a golden "i" accent.
 */
final class TailnetLogoView extends View {
    private static final int TEXT_COLOR = 0xFF202124;
    private static final int ACCENT_COLOR = 0xFFD6A23A;
    private static final String WORDMARK = "Tailnet";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    TailnetLogoView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.LEFT);
        setClickable(false);
        setFocusable(false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        float textSize = 34f * density;
        paint.setTextSize(textSize);
        float width = paint.measureText(WORDMARK) + dp(20);
        float height = textSize * 1.25f;
        setMeasuredDimension(resolveSize(Math.round(width), widthSpec),
                resolveSize(Math.round(height), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        float textSize = 34f * density;
        paint.setTextSize(textSize);
        float baseline = height * .5f - (paint.ascent() + paint.descent()) * .5f;
        float wordmarkWidth = paint.measureText(WORDMARK);
        float startX = Math.max(dp(10), (width - wordmarkWidth) * .5f);

        paint.setColor(TEXT_COLOR);
        canvas.drawText(WORDMARK, startX, baseline, paint);
        paint.setColor(ACCENT_COLOR);
        canvas.drawText("i", startX + paint.measureText("Ta"), baseline, paint);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
