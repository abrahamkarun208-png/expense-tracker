package com.local.expensetracker;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TxnAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Transaction> items;
    private final SimpleDateFormat df =
        new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

    public TxnAdapter(Context c, List<Transaction> items) {
        this.inflater = LayoutInflater.from(c);
        this.items = items;
    }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int p) { return items.get(p); }
    @Override public long getItemId(int p) { return p; }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) v = inflater.inflate(R.layout.item_txn, parent, false);
        Transaction t = items.get(pos);

        TextView badge = v.findViewById(R.id.bankBadge);
        badge.setText(shortCode(t.bankCode));

        TextView merchant = v.findViewById(R.id.merchant);
        String m = t.merchant == null || t.merchant.isEmpty()
            ? typeLabel(t.type) : t.merchant;
        if ("CARD_SPEND".equals(t.type) && !t.card4.isEmpty()) {
            m += "  \u2022\u2022\u2022\u2022 " + t.card4;
        }
        merchant.setText(m);

        TextView sub = v.findViewById(R.id.sub);
        sub.setText(t.bankName + " \u00B7 " + df.format(new Date(t.ts)));

        TextView amount = v.findViewById(R.id.amount);
        amount.setText(fmt(t));
        if ("CREDIT".equals(t.type)) {
            amount.setTextColor(Color.parseColor("#2E7D32"));
        } else if ("DEBIT".equals(t.type) || "CARD_SPEND".equals(t.type)) {
            amount.setTextColor(Color.parseColor("#C62828"));
        } else {
            amount.setTextColor(Color.parseColor("#424242"));
        }

        TextView pill = v.findViewById(R.id.typePill);
        pill.setText(typeLabel(t.type));
        int bg;
        switch (t.type) {
            case "DEBIT": bg = R.drawable.pill_debit; break;
            case "CREDIT": bg = R.drawable.pill_credit; break;
            case "TRANSFER": bg = R.drawable.pill_transfer; break;
            default: bg = R.drawable.pill_card; break;
        }
        pill.setBackgroundResource(bg);
        return v;
    }

    private static String shortCode(String code) {
        if (code == null) return "?";
        return code.length() <= 6 ? code : code.substring(0, 6);
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
