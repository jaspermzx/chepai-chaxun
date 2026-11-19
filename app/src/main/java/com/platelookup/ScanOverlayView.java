package com.platelookup;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;

/** 取景框覆盖层：四周压暗 + 四点角标 + 来回扫描线。 */
public class ScanOverlayView extends View {

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF window = new RectF();

    private final float radius;
    private final float cornerLen;
    private float sweep;
    private ValueAnimator animator;

    public ScanOverlayView(Context context) {
        this(context, null);
    }

    public ScanOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        radius = 12 * d;
        cornerLen = 22 * d;

        maskPaint.setColor(0x99000000);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1 * d);
        borderPaint.setColor(0x33FFFFFF);

        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(4 * d);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        cornerPaint.setColor(ContextCompat.getColor(context, R.color.accent));

        linePaint.setStrokeWidth(2 * d);
        linePaint.setColor(ContextCompat.getColor(context, R.color.accent));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float winW = w * 0.86f;
        float winH = winW / 3.0f;               // 车牌大致 3:1
        float left = (w - winW) / 2f;
        float centerY = h * 0.42f;
        window.set(left, centerY - winH / 2f, left + winW, centerY + winH / 2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (window.isEmpty()) {
            return;
        }
        int w = getWidth();
        int h = getHeight();

        // 四周压暗（分成四块，避开取景框）
        canvas.drawRect(0, 0, w, window.top, maskPaint);
        canvas.drawRect(0, window.bottom, w, h, maskPaint);
        canvas.drawRect(0, window.top, window.left, window.bottom, maskPaint);
        canvas.drawRect(window.right, window.top, w, window.bottom, maskPaint);

        canvas.drawRoundRect(window, radius, radius, borderPaint);

        // 四个角标
        float cx = cornerLen;
        float cy = cornerLen;
        // 左上
        canvas.drawLine(window.left, window.top + cy, window.left, window.top, cornerPaint);
        canvas.drawLine(window.left, window.top, window.left + cx, window.top, cornerPaint);
        // 右上
        canvas.drawLine(window.right, window.top + cy, window.right, window.top, cornerPaint);
        canvas.drawLine(window.right, window.top, window.right - cx, window.top, cornerPaint);
        // 左下
        canvas.drawLine(window.left, window.bottom - cy, window.left, window.bottom, cornerPaint);
        canvas.drawLine(window.left, window.bottom, window.left + cx, window.bottom, cornerPaint);
        // 右下
        canvas.drawLine(window.right, window.bottom - cy, window.right, window.bottom, cornerPaint);
        canvas.drawLine(window.right, window.bottom, window.right - cx, window.bottom, cornerPaint);

        // 扫描线
        float y = window.top + sweep * window.height();
        canvas.drawLine(window.left + 6, y, window.right - 6, y, linePaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2200);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            sweep = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }
}
