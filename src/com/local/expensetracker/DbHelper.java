/* Copyright (c) 2026 Chris. All rights reserved. */

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
    private static final int VER = 2;

    public DbHelper(Context c) {
        super(c, DB, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE txns(_key TEXT PRIMARY KEY, bankCode TEXT, bankName TEXT,"
            + " amount REAL, type TEXT, merchant TEXT, ts INTEGER, card4 TEXT,"
            + " currency TEXT NOT NULL DEFAULT 'INR')");
        db.execSQL("CREATE INDEX idx_txns_ts ON txns(ts)");
        db.execSQL("CREATE TABLE dues(cardKey TEXT PRIMARY KEY, bankName TEXT, card4 TEXT,"
            + " totalDue REAL, minDue REAL, dueTs INTEGER, status TEXT, paid REAL,"
            + " currency TEXT NOT NULL DEFAULT 'INR')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v2: per-transaction currency for Qatar/Australia support.
        if (oldV < 2) {
            db.execSQL("ALTER TABLE txns ADD COLUMN currency TEXT NOT NULL DEFAULT 'INR'");
            db.execSQL("ALTER TABLE dues ADD COLUMN currency TEXT NOT NULL DEFAULT 'INR'");
        }
    }

    public boolean txnExists(String key) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT 1 FROM txns WHERE _key=?", new String[]{key});
        boolean exists = c.moveToFirst();
        c.close();
        return exists;
    }

    /**
     * Near-duplicate check: same SMS can arrive with a slightly different
     * timestamp (live receiver vs inbox scan, multipart parts, OEM dup rows).
     * Treat same bank/amount/type/merchant within 60s as the same message.
     */
    public boolean hasNearDuplicate(String bankCode, double amount, String type,
                                    String merchant, long ts) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT 1 FROM txns WHERE bankCode=? AND ABS(amount-?)<0.005 AND type=?"
                + " AND COALESCE(merchant,'')=? AND ABS(ts-?)<=60000 LIMIT 1",
            new String[]{bankCode, String.valueOf(amount), type,
                         merchant == null ? "" : merchant, String.valueOf(ts)});
        boolean hit = c.moveToFirst();
        c.close();
        return hit;
    }

    /**
     * One-time cleanup of duplicates already stored: keep the earliest row
     * of each near-duplicate group, delete the rest.
     */
    public void removeNearDuplicates() {
        getWritableDatabase().execSQL(
            "DELETE FROM txns WHERE _key IN ("
                + "SELECT t2._key FROM txns t2 JOIN txns t1"
                + " ON t1._key<>t2._key"
                + " AND t1.bankCode=t2.bankCode"
                + " AND ABS(t1.amount-t2.amount)<0.005"
                + " AND t1.type=t2.type"
                + " AND COALESCE(t1.merchant,'')=COALESCE(t2.merchant,'')"
                + " AND ABS(t1.ts-t2.ts)<=60000"
                + " AND (t1.ts<t2.ts OR (t1.ts=t2.ts AND t1._key<t2._key)))");
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
        v.put("currency", t.currency == null ? "INR" : t.currency);
        getWritableDatabase().insertWithOnConflict("txns", null, v,
            SQLiteDatabase.CONFLICT_IGNORE);
    }

    public List<Transaction> getTxnsSince(long cutoffTs) {
        List<Transaction> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT _key,bankCode,bankName,amount,type,merchant,ts,card4,currency FROM txns"
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
            t.currency = c.getString(8);
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

    /** Income (CREDIT) total in a range. Salary, refunds, credits from others. */
    public double sumIncomeBetween(long startTs, long endTs) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT SUM(amount) FROM txns WHERE ts>=? AND ts<=?"
                + " AND type='CREDIT'",
            new String[]{String.valueOf(startTs), String.valueOf(endTs)});
        double s = c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : 0;
        c.close();
        return s;
    }

    /** Income per day (CREDIT), keyed by day-start millis. */
    public java.util.Map<Long, Double> getDailyIncome(long startTs, long endTs) {
        java.util.Map<Long, Double> out = new java.util.HashMap<Long, Double>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT ts, amount FROM txns WHERE ts>=? AND ts<=?"
                + " AND type='CREDIT'",
            new String[]{String.valueOf(startTs), String.valueOf(endTs)});
        while (c.moveToNext()) {
            long day = TxnGrouper.dayStart(c.getLong(0));
            Double v = out.get(day);
            out.put(day, (v == null ? 0 : v) + c.getDouble(1));
        }
        c.close();
        return out;
    }

    /** Total of card dues still unpaid (pending or overdue). */
    public double unpaidDuesTotal() {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT SUM(totalDue) FROM dues WHERE status IN ('PENDING','OUTSTANDING')",
            null);
        double s = c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : 0;
        c.close();
        return s;
    }

    /** Per-account income/expenses for a range, one row per bank + last-4. */
    public static class AccountSummary {
        public String bankCode;
        public String bankName;
        public String card4;
        public String currency;
        public double income;
        public double expenses;
        /** True when the group is card swipes only (no bank debits/credits). */
        public boolean isCard;
    }

    public List<AccountSummary> accountSummaries(long startTs, long endTs) {
        List<AccountSummary> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT bankCode, bankName, card4, currency,"
                + " SUM(CASE WHEN type='CREDIT' THEN amount ELSE 0 END),"
                + " SUM(CASE WHEN type IN ('DEBIT','CARD_SPEND') THEN amount ELSE 0 END),"
                + " SUM(CASE WHEN type='CARD_SPEND' THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN type IN ('DEBIT','CREDIT') THEN 1 ELSE 0 END)"
                + " FROM txns WHERE ts>=? AND ts<=?"
                + " AND type IN ('CREDIT','DEBIT','CARD_SPEND')"
                + " GROUP BY bankCode, bankName, card4, currency"
                + " ORDER BY 6 DESC",
            new String[]{String.valueOf(startTs), String.valueOf(endTs)});
        while (c.moveToNext()) {
            AccountSummary a = new AccountSummary();
            a.bankCode = c.getString(0);
            a.bankName = c.getString(1);
            a.card4 = c.getString(2);
            a.currency = c.getString(3);
            a.income = c.getDouble(4);
            a.expenses = c.getDouble(5);
            a.isCard = c.getInt(6) > 0 && c.getInt(7) == 0;
            out.add(a);
        }
        c.close();
        return out;
    }

    /** The earliest unpaid due, for the "due <date>" callout. May be null. */
    public CardDue earliestUnpaidDue() {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT cardKey,bankName,card4,totalDue,minDue,dueTs,status,paid,currency FROM dues"
                + " WHERE status IN ('PENDING','OUTSTANDING')"
                + " ORDER BY dueTs ASC LIMIT 1", null);
        CardDue d = null;
        if (c.moveToFirst()) {
            d = new CardDue();
            d.cardKey = c.getString(0);
            d.bankName = c.getString(1);
            d.card4 = c.getString(2);
            d.totalDue = c.getDouble(3);
            d.minDue = c.getDouble(4);
            d.dueTs = c.getLong(5);
            d.status = c.getString(6);
            d.paid = c.getDouble(7);
            d.currency = c.getString(8);
        }
        c.close();
        return d;
    }

    /** Spending per day (DEBIT + CARD_SPEND), keyed by day-start millis. */
    public java.util.Map<Long, Double> getDailySpending(long startTs, long endTs) {
        java.util.Map<Long, Double> out = new java.util.HashMap<Long, Double>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT ts, amount FROM txns WHERE ts>=? AND ts<=?"
                + " AND type IN ('DEBIT','CARD_SPEND')",
            new String[]{String.valueOf(startTs), String.valueOf(endTs)});
        while (c.moveToNext()) {
            long day = TxnGrouper.dayStart(c.getLong(0));
            Double v = out.get(day);
            out.put(day, (v == null ? 0 : v) + c.getDouble(1));
        }
        c.close();
        return out;
    }

    public List<Transaction> getTxnsBetween(long startTs, long endTs) {
        List<Transaction> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT _key,bankCode,bankName,amount,type,merchant,ts,card4,currency FROM txns"
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
            t.currency = c.getString(8);
            out.add(t);
        }
        c.close();
        return out;
    }

    /** Reclassify past self-transfer legs as TRANSFER once the user sets
     *  their names (the merchant field holds the counterparty name).
     *  Returns the number of rows fixed. */
    public int reclassifySelfTransfers(String... names) {
        java.util.List<String> ns = new java.util.ArrayList<String>();
        if (names != null) {
            for (String name : names) {
                String n = name == null ? "" : name.toLowerCase(
                    java.util.Locale.US).trim().replaceAll("\\s+", " ");
                if (n.length() >= 3 && !ns.contains(n)) ns.add(n);
            }
        }
        if (ns.isEmpty()) return 0;
        StringBuilder like = new StringBuilder();
        for (int i = 0; i < ns.size(); i++) {
            if (i > 0) like.append(" OR ");
            like.append("lower(merchant) LIKE '%' || ? || '%'");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE txns SET type='TRANSFER'"
            + " WHERE type IN ('CREDIT','DEBIT')"
            + " AND merchant IS NOT NULL AND (" + like + ")",
            ns.toArray(new Object[0]));
        android.database.Cursor c = db.rawQuery("SELECT changes()", null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    /** Mark one transaction as TRANSFER (used by transfer pair-matching). */
    public void markTransfer(String key) {
        getWritableDatabase().execSQL("UPDATE txns SET type='TRANSFER' WHERE _key=?",
            new Object[]{key});
    }

    /**
     * Find a same-day, same-amount transfer pair candidate. A CREDIT pairs
     * against a DEBIT/TRANSFER leg and vice versa, from a different account.
     * Needed because some banks omit the sender name entirely
     * ("Rs. 18,000 credited to your ESAF a/c ..."), so amount+day is the
     * only signal. Conservative: exact amount, same calendar day, different
     * account, Rs. 1,000 floor. Returns the candidate's _key, or null.
     */
    public String findPairCandidate(double amount, long ts, String bankCode,
            String card4, boolean wantCredit, java.util.Set<String> excludeKeys) {
        if (amount < 1000) return null;
        long dayStart = TxnGrouper.dayStart(ts);
        long dayEnd = dayStart + 86400000L;
        String wantTypes = wantCredit ? "'CREDIT'" : "'DEBIT','TRANSFER'";
        android.database.Cursor c = getReadableDatabase().rawQuery(
            "SELECT _key FROM txns"
            + " WHERE ABS(amount - ?) < 0.01"
            + " AND ts >= ? AND ts < ?"
            + " AND type IN (" + wantTypes + ")"
            + " AND NOT (bankCode = ? AND IFNULL(card4,'') = IFNULL(?,''))"
            + " ORDER BY ABS(ts - ?) LIMIT 10",
            new String[]{String.valueOf(amount), String.valueOf(dayStart),
                String.valueOf(dayEnd), bankCode == null ? "" : bankCode,
                card4 == null ? "" : card4, String.valueOf(ts)});
        String found = null;
        while (c.moveToNext()) {
            String k = c.getString(0);
            if (excludeKeys != null && excludeKeys.contains(k)) continue;
            found = k;
            break;
        }
        c.close();
        return found;
    }

    /** Repair pass over existing data: pair nameless self-transfer legs
     *  already in the DB. Idempotent. Returns the number of credits fixed. */
    public int repairTransferPairs() {
        int fixed = 0;
        java.util.Set<String> used = new java.util.HashSet<String>();
        android.database.Cursor c = getReadableDatabase().rawQuery(
            "SELECT _key, amount, ts, bankCode, card4 FROM txns"
            + " WHERE type='CREDIT' AND amount >= 1000 ORDER BY ts", null);
        java.util.List<String[]> rows = new java.util.ArrayList<String[]>();
        while (c.moveToNext()) {
            rows.add(new String[]{c.getString(0), String.valueOf(c.getDouble(1)),
                String.valueOf(c.getLong(2)), c.getString(3), c.getString(4)});
        }
        c.close();
        for (String[] r : rows) {
            if (used.contains(r[0])) continue;
            String pair = findPairCandidate(Double.parseDouble(r[1]),
                Long.parseLong(r[2]), r[3], r[4], false, used);
            if (pair != null) {
                SQLiteDatabase wdb = getWritableDatabase();
                wdb.execSQL("UPDATE txns SET type='TRANSFER' WHERE _key=?",
                    new Object[]{r[0]});
                wdb.execSQL("UPDATE txns SET type='TRANSFER' WHERE _key=?",
                    new Object[]{pair});
                used.add(r[0]);
                used.add(pair);
                fixed++;
            }
        }
        return fixed;
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
        v.put("currency", d.currency == null ? "INR" : d.currency);
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
            "SELECT cardKey,bankName,card4,totalDue,minDue,dueTs,status,paid,currency FROM dues"
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
            d.currency = c.getString(8);
            out.add(d);
        }
        c.close();
        return out;
    }
}
