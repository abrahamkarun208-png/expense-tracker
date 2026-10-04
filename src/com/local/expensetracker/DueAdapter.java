/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DueAdapter extends BaseAdapter {

    private final Context ctx;
    private final LayoutInflater inflater;
    private final List<CardDue> items;
    private final SimpleDateFormat df =
        new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    public DueAdapter(Context c, List<CardDue> items) {
        this.ctx = c;
        this.inflater = LayoutInflater.from(c);
        this.items = items;
    }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int p) { return items.get(p); }
    @Override public long getItemId(int p) { return p; }

    /** Renders all rows into a LinearLayout (for use inside a ScrollView). */
    public void populate(android.widget.LinearLayout container) {
        container.removeAllViews();
        for (int i = 0; i < getCount(); i++) {
            container.addView(getView(i, null, container));
        }
    }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) v = inflater.inflate(R.layout.item_due, parent, false);
        CardDue d = items.get(pos);

        TextView name = v.findViewById(R.id.cardName);
        String label = d.bankName + (d.card4 == null || d.card4.isEmpty()
            ? " Card" : " \u2022\u2022\u2022\u2022 " + d.card4);
        name.setText(label);

        TextView pill = v.findViewById(R.id.statusPill);
        if ("PAID".equals(d.status)) {
            pill.setText("Paid");
            pill.setBackgroundResource(R.drawable.pill_credit);
            pill.setTextColor(ctx.getColor(R.color.credit));
        } else {
            pill.setText("Outstanding");
            pill.setBackgroundResource(R.drawable.pill_debit);
            pill.setTextColor(ctx.getColor(R.color.debit));
        }

        TextView dueLine = v.findViewById(R.id.dueLine);
        if ("PAID".equals(d.status)) {
            dueLine.setText("Paid " + MoneyFmt.money(d.paid, d.currency));
        } else {
            dueLine.setText("Due " + MoneyFmt.money(d.totalDue, d.currency)
                + "  (min " + MoneyFmt.money(d.minDue, d.currency) + ")");
        }

        TextView dateLine = v.findViewById(R.id.dueDateLine);
        if ("PAID".equals(d.status)) {
            dateLine.setText("");
        } else if ("OUTSTANDING".equals(d.status)
                && d.dueTs < System.currentTimeMillis()) {
            dateLine.setText("Due date was " + df.format(new Date(d.dueTs)) + " \u2014 missed");
            dateLine.setTextColor(ctx.getColor(R.color.debit));
        } else {
            dateLine.setText("Due by " + df.format(new Date(d.dueTs)));
            dateLine.setTextColor(ctx.getColor(R.color.muted));
        }
        return v;
    }

}
