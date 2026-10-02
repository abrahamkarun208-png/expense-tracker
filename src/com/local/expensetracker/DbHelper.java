package com.local.expensetracker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** All data stays in this on-device SQLite database. No network access. */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB = "expenses.db";
    private static final int VER = 1;

    public DbHelper(Context c) {
        super(c, DB, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE txns(_key TEXT PRIMARY KEY, bankCode TEXT, bankName TEXT,"
            + " amount REAL, type TEXT, merchant TEXT, ts INTEGER, card4 TEXT)");
        db.execSQL("CREATE INDEX idx_txns_ts ON txns(ts)");
        db.execSQL("CREATE TABLE dues(cardKey TEXT PRIMARY KEY, bankName TEXT, card4 TEXT,"
            + " totalDue REAL, minDue REAL, dueTs INTEGER, status TEXT, paid REAL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
    }

    public boolean txnExists(String key) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT 1 FROM txns WHERE _key=?", new String[]{key});
        boolean exists = c.moveToFirst();
        c.close();
        return exists;
    }

    public void insertTxn(Transaction t) {
        ContentValues v = new ContentValues();
        v.put("_key", t.key);
        v.put("bankCode", t.bankCode);
        v.put("bankName", t.bankName);
        v.put("amount", t.amount);
        v.put("type", t.type);
        v.put("merchant", t.merchant);
        v.put("ts", t.ts);
        v.put("card4", t.card4);
        getWritableDatabase().insertWithOnConflict("txns", null, v,
            SQLiteDatabase.CONFLICT_IGNORE);
    }

    public List<Transaction> getTxnsSince(long cutoffTs) {
        List<Transaction> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT _key,bankCode,bankName,amount,type,merchant,ts,card4 FROM txns"
                + " WHERE ts>=? ORDER BY ts DESC",
            new String[]{String.valueOf(cutoffTs)});
        while (c.moveToNext()) {
            Transaction t = new Transaction();
            t.key = c.getString(0);
            t.bankCode = c.getString(1);
            t.bankName = c.getString(2);
            t.amount = c.getDouble(3);
            t.type = c.getString(4);
            t.merchant = c.getString(5);
            t.ts = c.getLong(6);
            t.card4 = c.getString(7);
            out.add(t);
        }
        c.close();
        return out;
    }

    public double sumSpentSince(long cutoffTs) {
        return sumSpentBetween(cutoffTs, Long.MAX_VALUE);
    }

    public double sumSpentBetween(long startTs, long endTs) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT SUM(amount) FROM txns WHERE ts>=? AND ts<=?"
                + " AND type IN ('DEBIT','CARD_SPEND')",
            new String[]{String.valueOf(startTs), String.valueOf(endTs)});
        double s = c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : 0;
        c.close();
        return s;
    }

    public List<Transaction> getTxnsBetween(long startTs, long endTs) {
        List<Transaction> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT _key,bankCode,bankName,amount,type,merchant,ts,card4 FROM txns"
                + " WHERE ts>=? AND ts<=? ORDER BY ts DESC",
            new String[]{String.valueOf(startTs), String.valueOf(endTs)});
        while (c.moveToNext()) {
            Transaction t = new Transaction();
            t.key = c.getString(0);
            t.bankCode = c.getString(1);
            t.bankName = c.getString(2);
            t.amount = c.getDouble(3);
            t.type = c.getString(4);
            t.merchant = c.getString(5);
            t.ts = c.getLong(6);
            t.card4 = c.getString(7);
            out.add(t);
        }
        c.close();
        return out;
    }

    public void upsertDue(CardDue d) {
        ContentValues v = new ContentValues();
        v.put("cardKey", d.cardKey);
        v.put("bankName", d.bankName);
        v.put("card4", d.card4);
        v.put("totalDue", d.totalDue);
        v.put("minDue", d.minDue);
        v.put("dueTs", d.dueTs);
        v.put("status", "PENDING");
        v.put("paid", 0);
        getWritableDatabase().insertWithOnConflict("dues", null, v,
            SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Mark a due paid if a matching payment receipt arrives. */
    public boolean markPaid(String cardKey, double paidAmount) {
        ContentValues v = new ContentValues();
        v.put("status", "PAID");
        v.put("paid", paidAmount);
        int n = getWritableDatabase().update("dues", v,
            "cardKey=? AND status IN ('PENDING','OUTSTANDING')",
            new String[]{cardKey});
        if (n == 0) {
            // Payment without a known statement: try any card of the same bank.
            String bank = cardKey.contains("|") ? cardKey.substring(0, cardKey.indexOf('|')) : cardKey;
            n = getWritableDatabase().update("dues", v,
                "cardKey LIKE ? AND status IN ('PENDING','OUTSTANDING')",
                new String[]{bank + "|%"});
        }
        return n > 0;
    }

    public void refreshOverdue(long now) {
        ContentValues v = new ContentValues();
        v.put("status", "OUTSTANDING");
        getWritableDatabase().update("dues", v,
            "status='PENDING' AND dueTs<?", new String[]{String.valueOf(now)});
    }

    public List<CardDue> getDues() {
        List<CardDue> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT cardKey,bankName,card4,totalDue,minDue,dueTs,status,paid FROM dues"
                + " ORDER BY dueTs ASC", null);
        while (c.moveToNext()) {
            CardDue d = new CardDue();
            d.cardKey = c.getString(0);
            d.bankName = c.getString(1);
            d.card4 = c.getString(2);
            d.totalDue = c.getDouble(3);
            d.minDue = c.getDouble(4);
            d.dueTs = c.getLong(5);
            d.status = c.getString(6);
            d.paid = c.getDouble(7);
            out.add(d);
        }
        c.close();
        return out;
    }
}
