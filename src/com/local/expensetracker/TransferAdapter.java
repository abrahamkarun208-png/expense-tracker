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

/** Consolidated Transfers section: one row per transfer (paired legs merged). */
public class TransferAdapter extends BaseAdapter {

    private final Context ctx;
    private final LayoutInflater inflater;
    private final List<TransferGrouper.Entry> items;
    private final SimpleDateFormat df =
        new SimpleDateFormat("d MMM, h:mm a", Locale.getDefault());

    public TransferAdapter(Context c, List<TransferGrouper.Entry> items) {
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
        if (v == null) v = inflater.inflate(R.layout.item_txn, parent, false);
        TransferGrouper.Entry e = items.get(pos);
        int color = ctx.getColor(R.color.transfer);

        TextView badge = v.findViewById(R.id.badge);
        badge.setText("T");
        badge.setTextColor(color);

        TextView merchant = v.findViewById(R.id.merchant);
        merchant.setText(TransferGrouper.route(e));

        TextView sub = v.findViewById(R.id.sub);
        sub.setText(df.format(new Date(e.ts)) + " \u00B7 Transfer");

        TextView amount = v.findViewById(R.id.amount);
        amount.setText(MoneyFmt.money(e.amount, e.currency));
        amount.setTextColor(color);
        return v;
    }
}
