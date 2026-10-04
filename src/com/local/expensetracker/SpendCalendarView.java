/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Month calendar with a dot under every day that has spending
 * (DEBIT / CARD_SPEND) and a green dot for days with income (CREDIT).
 * Pure Android views, no dependencies.
 */
public class SpendCalendarView extends LinearLayout {

    public interface OnDaySelectListener {
        void onDaySelect(long dayStartMs);
    }

    /** Days with spending above this get a red dot, at/below get a green dot. */
    private static final double SPEND_ALERT = 1500;

    private int year, month; // month is 0-based
    private long selectedDay;
    private Map<Long, Double> spendByDay = new HashMap<Long, Double>();
    private Map<Long, Double> incomeByDay = new HashMap<Long, Double>();
    private OnDaySelectListener listener;

    private TextView titleView;
    private Button prevBtn, nextBtn;
    private LinearLayout gridBox;
    private final SimpleDateFormat titleFmt =
        new SimpleDateFormat("MMMM yyyy", Locale.getDefault());

    public SpendCalendarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        build();
        showCurrentMonth();
    }

    public void setOnDaySelectListener(OnDaySelectListener l) {
        this.listener = l;
    }

    public int getYear() { return year; }
    public int getMonth() { return month; }

    public boolean hasSpending(long dayStart) {
        Double v = spendByDay.get(dayStart);
        return v != null && v > 0;
    }

    public void showCurrentMonth() {
        Calendar c = Calendar.getInstance();
        year = c.get(Calendar.YEAR);
        month = c.get(Calendar.MONTH);
        selectedDay = TxnGrouper.dayStart(c.getTimeInMillis());
        render();
    }

    public void setState(int year, int month, long selectedDay,
                         Map<Long, Double> spend, Map<Long, Double> income) {
        this.year = year;
        this.month = month;
        this.selectedDay = selectedDay;
        this.spendByDay = spend == null ? new HashMap<Long, Double>() : spend;
        this.incomeByDay = income == null ? new HashMap<Long, Double>() : income;
        render();
    }

    private void build() {
        Context ctx = getContext();

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        prevBtn = navButton(ctx, "\u2039");
        nextBtn = navButton(ctx, "\u203A");
        prevBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { shiftMonth(-1); }
        });
        nextBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { shiftMonth(1); }
        });

        titleView = new TextView(ctx);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(ctx.getColor(R.color.ink));
        LayoutParams tlp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        titleView.setLayoutParams(tlp);

        header.addView(prevBtn);
        header.addView(titleView);
        header.addView(nextBtn);
        addView(header);

        LinearLayout week = new LinearLayout(ctx);
        week.setOrientation(HORIZONTAL);
        String[] names = {"S", "M", "T", "W", "T", "F", "S"};
        for (String n : names) {
            TextView tw = new TextView(ctx);
            tw.setText(n);
            tw.setGravity(Gravity.CENTER);
            tw.setTextSize(12);
            tw.setTextColor(ctx.getColor(R.color.muted));
            tw.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            week.addView(tw);
        }
        LayoutParams wlp = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(6);
        wlp.bottomMargin = dp(2);
        week.setLayoutParams(wlp);
        addView(week);

        gridBox = new LinearLayout(ctx);
        gridBox.setOrientation(VERTICAL);
        addView(gridBox);
    }

    private Button navButton(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(24);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(ctx.getColor(R.color.primary));
        b.setBackgroundResource(0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        int p = dp(10);
        b.setPadding(p, dp(2), p, dp(2));
        return b;
    }

    private void shiftMonth(int delta) {
        Calendar c = Calendar.getInstance();
        c.set(year, month, 1);
        c.add(Calendar.MONTH, delta);
        Calendar now = Calendar.getInstance();
        // Never navigate past the current month.
        if (c.get(Calendar.YEAR) > now.get(Calendar.YEAR)
                || (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && c.get(Calendar.MONTH) > now.get(Calendar.MONTH))) {
            return;
        }
        year = c.get(Calendar.YEAR);
        month = c.get(Calendar.MONTH);
        render();
    }

    private void render() {
        Context ctx = getContext();
        Calendar c = Calendar.getInstance();
        c.set(year, month, 1);
        titleView.setText(titleFmt.format(c.getTime()));

        Calendar now = Calendar.getInstance();
        boolean isCurrent = year == now.get(Calendar.YEAR)
            && month == now.get(Calendar.MONTH);
        nextBtn.setEnabled(!isCurrent);
        nextBtn.setAlpha(isCurrent ? 0.3f : 1f);

        gridBox.removeAllViews();
        int offset = c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY; // 0=Sun
        int daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        long todayStart = TxnGrouper.dayStart(System.currentTimeMillis());

        int day = 1;
        for (int r = 0; r < 6; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            boolean rowHasDay = false;
            for (int col = 0; col < 7; col++) {
                int idx = r * 7 + col;
                LinearLayout cell;
                if (idx < offset || day > daysInMonth) {
                    cell = emptyCell(ctx);
                } else {
                    rowHasDay = true;
                    c.set(year, month, day);
                    long dayStart = TxnGrouper.dayStart(c.getTimeInMillis());
                    boolean future = dayStart > todayStart;
                    boolean selected = dayStart == selectedDay;
                    Double sv = spendByDay.get(dayStart);
                    double spent = sv == null ? 0 : sv;
                    boolean hasSpend = !future && spent > 0;
                    Double iv = incomeByDay.get(dayStart);
                    boolean hasIncome = !future && iv != null && iv > 0;
                    cell = dayCell(ctx, day, selected, hasSpend, spent,
                        hasIncome, future, dayStart);
                    day++;
                }
                row.addView(cell);
            }
            if (!rowHasDay) break;
            gridBox.addView(row);
        }
    }

    private LinearLayout emptyCell(Context ctx) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        cell.setMinimumHeight(dp(48));
        return cell;
    }

    private LinearLayout dayCell(Context ctx, int day, boolean selected,
                                 boolean hasSpend, double spent, boolean hasIncome,
                                 boolean future, final long dayStart) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        cell.setPadding(0, dp(4), 0, dp(4));
        cell.setMinimumHeight(dp(48));

        TextView num = new TextView(ctx);
        num.setText(String.valueOf(day));
        num.setTextSize(14);
        num.setGravity(Gravity.CENTER);
        int sz = dp(36);
        num.setLayoutParams(new LayoutParams(sz, sz));
        if (selected) {
            num.setBackgroundResource(R.drawable.day_selected);
            num.setTextColor(0xFFFFFFFF);
            num.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            num.setTextColor(ctx.getColor(R.color.ink));
            if (future) num.setAlpha(0.35f);
        }

        View dot = new View(ctx);
        int d = dp(7);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(d, d);
        dlp.topMargin = dp(3);
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        dot.setLayoutParams(dlp);
        dot.setBackgroundResource(
            spent > SPEND_ALERT ? R.drawable.dot_over : R.drawable.dot_under);
        dot.setVisibility(hasSpend ? VISIBLE : INVISIBLE);

        View incomeDot = new View(ctx);
        LinearLayout.LayoutParams idlp = new LinearLayout.LayoutParams(d, d);
        idlp.topMargin = dp(2);
        idlp.gravity = Gravity.CENTER_HORIZONTAL;
        incomeDot.setLayoutParams(idlp);
        incomeDot.setBackgroundResource(R.drawable.dot_income);
        incomeDot.setVisibility(hasIncome ? VISIBLE : INVISIBLE);

        cell.addView(num);
        cell.addView(dot);
        cell.addView(incomeDot);

        if (!future) {
            cell.setClickable(true);
            cell.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    selectedDay = dayStart;
                    render();
                    if (listener != null) listener.onDaySelect(dayStart);
                }
            });
        }
        return cell;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
