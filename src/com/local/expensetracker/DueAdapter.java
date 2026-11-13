/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DueAdapter extends BaseAdapter {

    /** Called after the user changes a card's auto-pay mode. The argument is
     *  true when auto-pay is now on (TOTAL or MINIMUM). */
    public interface OnAutoPayChanged {
        void onAutoPayChanged(boolean autoPayOn);
    }

    private final Context ctx;
    private final LayoutInflater inflater;
    private final List<CardDue> items;
    private final OnAutoPayChanged callback;
    private final SimpleDateFormat df =
        new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    public DueAdapter(Context c, List<CardDue> items) {
        this(c, items, null);
    }

    public DueAdapter(Context c, List<CardDue> items, OnAutoPayChanged cb) {
        this.ctx = c;
        this.inflater = LayoutInflater.from(c);
        this.items = items;
        this.callback = cb;
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
        final CardDue d = items.get(pos);

        TextView name = v.findViewById(R.id.cardName);
        String label = d.bankName + (d.card4 == null || d.card4.isEmpty()
            ? " Card" : " \u2022\u2022\u2022\u2022 " + d.card4);
        name.setText(label);

        boolean paidInFull = "PAID".equals(d.status);
        boolean partPaid = !paidInFull && d.paid > 0.005;
        double remaining = d.totalDue - d.paid;

        TextView pill = v.findViewById(R.id.statusPill);
        if (paidInFull) {
            pill.setText("Paid");
            pill.setBackgroundResource(R.drawable.pill_credit);
            pill.setTextColor(ctx.getColor(R.color.credit));
        } else if (partPaid) {
            pill.setText("Part paid");
            pill.setBackgroundResource(R.drawable.pill_transfer);
            pill.setTextColor(ctx.getColor(R.color.transfer));
        } else {
            pill.setText("Outstanding");
            pill.setBackgroundResource(R.drawable.pill_debit);
            pill.setTextColor(ctx.getColor(R.color.debit));
        }

        TextView dueLine = v.findViewById(R.id.dueLine);
        if (paidInFull) {
            String s = "Paid " + MoneyFmt.money(d.paid, d.currency);
            if ("AUTO".equals(d.lastPaySrc)) s += " via Auto Pay";
            dueLine.setText(s);
        } else {
            String s = "Due " + MoneyFmt.money(d.totalDue, d.currency)
                + "  (min " + MoneyFmt.money(d.minDue, d.currency) + ")";
            if (partPaid) {
                s += "  \u00B7  Paid " + MoneyFmt.money(d.paid, d.currency)
                    + "  \u00B7  Remaining "
                    + MoneyFmt.money(Math.max(remaining, 0), d.currency);
            }
            dueLine.setText(s);
        }

        TextView dateLine = v.findViewById(R.id.dueDateLine);
        String autoNote = autoPayNote(d);
        if (paidInFull) {
            dateLine.setText("");
        } else if ("OUTSTANDING".equals(d.status)
                && d.dueTs < System.currentTimeMillis()) {
            dateLine.setText("Due date was " + df.format(new Date(d.dueTs)) + " \u2014 missed"
                + autoNote);
            dateLine.setTextColor(ctx.getColor(R.color.debit));
        } else {
            dateLine.setText("Due by " + df.format(new Date(d.dueTs)) + autoNote);
            dateLine.setTextColor(ctx.getColor(R.color.muted));
        }

        // Tap a card to set its auto-pay mode: Off, Total due or Minimum due.
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                showAutoPayDialog(d);
            }
        });
        return v;
    }

    /** " \u00B7 Auto-pay: total due" / " \u00B7 Auto-pay: minimum due", or "". */
    private String autoPayNote(CardDue d) {
        if ("TOTAL".equals(d.autoPayMode)) {
            return "  \u00B7  Auto-pay: total due";
        }
        if ("MINIMUM".equals(d.autoPayMode)) {
            return "  \u00B7  Auto-pay: minimum due";
        }
        return "";
    }

    private void showAutoPayDialog(final CardDue d) {
        String[] modes = {"Off", "Total due", "Minimum due"};
        int current = "TOTAL".equals(d.autoPayMode) ? 1
            : "MINIMUM".equals(d.autoPayMode) ? 2 : 0;
        new AlertDialog.Builder(ctx)
            .setTitle("Auto-pay: " + d.bankName + " card")
            .setSingleChoiceItems(modes, current,
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String mode = which == 1 ? "TOTAL"
                            : which == 2 ? "MINIMUM" : "OFF";
                        new DbHelper(ctx).setAutoPayMode(d.cardKey, mode);
                        d.autoPayMode = mode;
                        dialog.dismiss();
                        Toast.makeText(ctx,
                            "Auto-pay set to "
                                + (which == 1 ? "Total due"
                                    : which == 2 ? "Minimum due" : "Off"),
                            Toast.LENGTH_SHORT).show();
                        if (callback != null) {
                            callback.onAutoPayChanged(!"OFF".equals(mode));
                        }
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }

}
