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

/**
 * Transaction list with day-group headers (TxnGrouper.DayHeader rows).
 * Items are Transaction or TxnGrouper.DayHeader.
 */
public class TxnAdapter extends BaseAdapter {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TXN = 1;

    private final Context ctx;
    private final LayoutInflater inflater;
    private final List<Object> items;
    private final SimpleDateFormat df =
        new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

    public TxnAdapter(Context c, List<Object> items) {
        this.ctx = c;
        this.inflater = LayoutInflater.from(c);
        this.items = items;
    }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int p) { return items.get(p); }
    @Override public long getItemId(int p) { return p; }
    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int p) {
        return items.get(p) instanceof TxnGrouper.DayHeader ? TYPE_HEADER : TYPE_TXN;
    }

    /** Renders all rows into a LinearLayout (for use inside a ScrollView). */
    public void populate(android.widget.LinearLayout container) {
        container.removeAllViews();
        for (int i = 0; i < getCount(); i++) {
            container.addView(getView(i, null, container));
        }
    }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        if (getItemViewType(pos) == TYPE_HEADER) {
            return headerView((TxnGrouper.DayHeader) items.get(pos), convertView, parent);
        }
        return txnView((Transaction) items.get(pos), convertView, parent);
    }

    private View headerView(TxnGrouper.DayHeader h, View v, ViewGroup parent) {
        if (v == null) v = inflater.inflate(R.layout.item_day_header, parent, false);
        TextView label = v.findViewById(R.id.dayLabel);
        // Mockup style: "TODAY \u00B7 SAT 3 OCT".
        String l = h.label.replace(", ", " \u00B7 ")
            .toUpperCase(Locale.getDefault());
        label.setText(l);
        TextView total = v.findViewById(R.id.dayTotal);
        String t = "Exp \u20B9" + String.format(Locale.US, "%,.0f", h.spent);
        if (h.income > 0) {
            t += " \u00B7 Inc \u20B9"
                + String.format(Locale.US, "%,.0f", h.income);
        }
        total.setText(t);
        return v;
    }

    private View txnView(Transaction t, View v, ViewGroup parent) {
        if (v == null) v = inflater.inflate(R.layout.item_txn, parent, false);

        int color;
        if ("CREDIT".equals(t.type)) {
            color = ctx.getColor(R.color.credit);
        } else if ("DEBIT".equals(t.type) || "CARD_SPEND".equals(t.type)) {
            color = ctx.getColor(R.color.debit);
        } else {
            color = ctx.getColor(R.color.transfer);
        }

        // Mockup style: letter badge tinted like the amount.
        TextView badge = v.findViewById(R.id.badge);
        String m0 = t.merchant == null || t.merchant.isEmpty()
            ? typeLabel(t.type) : t.merchant;
        badge.setText(m0.substring(0, 1).toUpperCase(Locale.getDefault()));
        badge.setTextColor(color);

        TextView merchant = v.findViewById(R.id.merchant);
        merchant.setText(m0);

        TextView sub = v.findViewById(R.id.sub);
        String s = t.bankName == null ? "" : t.bankName;
        if ("CARD_SPEND".equals(t.type) && t.card4 != null && !t.card4.isEmpty()) {
            s += " \u2022\u2022\u2022\u2022 " + t.card4;
        }
        s += (s.isEmpty() ? "" : " \u00B7 ")
            + new SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(new Date(t.ts));
        sub.setText(s);

        TextView amount = v.findViewById(R.id.amount);
        amount.setText(fmt(t));
        amount.setTextColor(color);
        return v;
    }

    private static String typeLabel(String type) {
        if ("CARD_SPEND".equals(type)) return "Card debit";
        if ("TRANSFER".equals(type)) return "Transfer";
        if ("CREDIT".equals(type)) return "Credit";
        return "Debit";
    }

    private static String fmt(Transaction t) {
        String a = "\u20B9" + String.format(Locale.US, "%,.0f", t.amount);
        if ("CREDIT".equals(t.type)) return "+" + a;
        if ("DEBIT".equals(t.type) || "CARD_SPEND".equals(t.type)) return "-" + a;
        return a;
    }
}
