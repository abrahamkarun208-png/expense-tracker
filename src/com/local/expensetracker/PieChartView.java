/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Simple donut chart drawn on canvas. No dependencies. */
public class PieChartView extends View {

    public static class Slice {
        public final String label;
        public final double value;
        public final int color;
        public Slice(String label, double value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    private List<Slice> slices = new ArrayList<Slice>();
    private double total = 0;
    private String centerText = "";
    private OnSliceClickListener listener;

    public interface OnSliceClickListener {
        void onSliceClick(int index);
    }

    public void setOnSliceClickListener(OnSliceClickListener l) {
        this.listener = l;
    }

    public PieChartView(Context context) {
        super(context);
    }

    public PieChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setData(List<Slice> slices, double total, String centerText) {
        this.slices = slices == null ? new ArrayList<Slice>() : slices;
        this.total = total;
        this.centerText = centerText == null ? "" : centerText;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int px = (int) (150 * getResources().getDisplayMetrics().density);
        int spec = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY);
        super.onMeasure(spec, spec);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 4 * density;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        if (slices.isEmpty() || total <= 0) {
            paint.setColor(0xFF2A3B2F);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setColor(getContext().getColor(R.color.card));
            canvas.drawCircle(cx, cy, r * 0.58f, paint);
            return;
        }

        RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
        float start = -90f;
        for (Slice s : slices) {
            float sweep = (float) (s.value / total * 360);
            if (sweep <= 0) continue;
            paint.setColor(s.color);
            // small gap between slices
            canvas.drawArc(oval, start, Math.max(sweep - 2f, 1f), true, paint);
            start += sweep;
        }

        // donut hole
        paint.setColor(getContext().getColor(R.color.card));
        canvas.drawCircle(cx, cy, r * 0.58f, paint);

        // center total
        paint.setColor(0xFFEFF4EF);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(14 * density);
        canvas.drawText(centerText, cx, cy + 5 * density, paint);
    }

    public static String money(double x) {
        return "\u20B9" + String.format(Locale.US, "%,.0f", x);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP
                || listener == null || slices.isEmpty() || total <= 0) {
            return super.onTouchEvent(event);
        }
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 4 * density;
        float dx = event.getX() - cx;
        float dy = event.getY() - cy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < r * 0.58f || dist > r) return false; // hole or outside
        // Canvas angles: 0 deg = 3 o'clock, clockwise positive (y points down).
        double ang = Math.toDegrees(Math.atan2(dy, dx));
        float norm = (float) ((ang + 90 + 360) % 360); // slices start at -90 (top)
        float acc = 0;
        for (int i = 0; i < slices.size(); i++) {
            float sweep = (float) (slices.get(i).value / total * 360);
            if (sweep <= 0) continue;
            if (norm >= acc && norm < acc + sweep) {
                listener.onSliceClick(i);
                return true;
            }
            acc += sweep;
        }
        return false;
    }
}
