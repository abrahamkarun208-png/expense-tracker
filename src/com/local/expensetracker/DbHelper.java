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
    private static final int VER = 6;

    public DbHelper(Context c) {
        super(c, DB, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE txns(_key TEXT PRIMARY KEY, bankCode TEXT, bankName TEXT,"
            + " amount REAL, type TEXT, merchant TEXT, ts INTEGER, card4 TEXT,"
            + " currency TEXT NOT NULL DEFAULT 'INR',"
            + " transferDir TEXT, pairKey TEXT, ref TEXT)");
        db.execSQL("CREATE INDEX idx_txns_ts ON txns(ts)");
        db.execSQL("CREATE TABLE dues(cardKey TEXT PRIMARY KEY, bankName TEXT, card4 TEXT,"
            + " totalDue REAL, minDue REAL, dueTs INTEGER, status TEXT, paid REAL,"
            + " currency TEXT NOT NULL DEFAULT 'INR',"
            + " autoPayMode TEXT NOT NULL DEFAULT 'OFF', lastPaySrc TEXT)");
        db.execSQL("CREATE TABLE balances(bankCode TEXT, acct4 TEXT, bankName TEXT,"
            + " balance REAL, ts INTEGER, currency TEXT NOT NULL DEFAULT 'INR',"
            + " PRIMARY KEY(bankCode, acct4))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v2: per-transaction currency for Qatar/Australia support.
        if (oldV < 2) {
            db.execSQL("ALTER TABLE txns ADD COLUMN currency TEXT NOT NULL DEFAULT 'INR'");
            db.execSQL("ALTER TABLE dues ADD COLUMN currency TEXT NOT NULL DEFAULT 'INR'");
        }
        // v3: auto-pay mode per card (OFF/TOTAL/MINIMUM) and the source of the
        // last payment (MANUAL/AUTO) so dues can equalize against payments.
        if (oldV < 3) {
            db.execSQL("ALTER TABLE dues ADD COLUMN autoPayMode TEXT NOT NULL DEFAULT 'OFF'");
            db.execSQL("ALTER TABLE dues ADD COLUMN lastPaySrc TEXT");
        }
        // v4: transfer direction (OUT/IN) and pair link, so the Transfers
        // view can consolidate the two legs of one inter-bank transfer.
        if (oldV < 4) {
            db.execSQL("ALTER TABLE txns ADD COLUMN transferDir TEXT");
            db.execSQL("ALTER TABLE txns ADD COLUMN pairKey TEXT");
        }
        // v5: transaction reference (Ref/UTR/RRN) for duplicate-proofing.
        if (oldV < 5) {
            db.execSQL("ALTER TABLE txns ADD COLUMN ref TEXT");
        }
        // v6: last-known balance per bank account (Avl Bal in SMS), for
        // the Bank status card. Old installs fill it from the once-per-
        // version full rescan, which re-reads every balance SMS.
        if (oldV < 6) {
            db.execSQL("CREATE TABLE IF NOT EXISTS balances(bankCode TEXT,"
                + " acct4 TEXT, bankName TEXT, balance REAL, ts INTEGER,"
                + " currency TEXT NOT NULL DEFAULT 'INR',"
                + " PRIMARY KEY(bankCode, acct4))");
        }
    }

    public boolean txnExists(String key) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT 1 FROM txns WHERE _key=?", new String[]{key});
        boolean exists = c.moveToFirst();
        c.close();
        return exists;
    }

    /** On rescan, update an already-imported row when the current parser
     *  classifies it differently (newer versions read direction better,
     *  e.g. NEFT completion advices once read as income). TRANSFER rows are
     *  never downgraded - they were deliberately excluded by name match or
     *  pair-matching. Also fills in account digits the old parser missed. */
    public void reclassifyIfChanged(String key, String newType, String newCard4) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT type, card4 FROM txns WHERE _key=?",
            new String[]{key});
        if (!c.moveToFirst()) { c.close(); return; }
        String oldType = c.getString(0);
        String oldCard4 = c.getString(1);
        c.close();
        boolean typeChange = !newType.equals(oldType)
            && !"TRANSFER".equals(oldType)
            && ("CREDIT".equals(oldType) || "DEBIT".equals(oldType));
        boolean cardChange = newCard4 != null && !newCard4.isEmpty()
            && (oldCard4 == null || oldCard4.isEmpty());
        if (!typeChange && !cardChange) return;
        if (typeChange && cardChange) {
            db.execSQL("UPDATE txns SET type=?, card4=? WHERE _key=?",
                new Object[]{newType, newCard4, key});
        } else if (typeChange) {
            db.execSQL("UPDATE txns SET type=? WHERE _key=?",
                new Object[]{newType, key});
        } else {
            db.execSQL("UPDATE txns SET card4=? WHERE _key=?",
                new Object[]{newCard4, key});
        }
    }

    /** Fill in missing card4/account digits on an already-imported row
     *  (newer parser versions extract digits older ones missed). */
    public void backfillCard4(String bankCode, double amount, String type,
                              String merchant, long ts, String card4) {
        if (card4 == null || card4.isEmpty()) return;
        getWritableDatabase().execSQL(
            "UPDATE txns SET card4=? WHERE bankCode=? AND ABS(amount-?)<0.005"
            + " AND type=? AND COALESCE(merchant,'')=?"
            + " AND ABS(ts-?)<=60000 AND (card4 IS NULL OR card4='')",
            new Object[]{card4, bankCode, amount, type,
                merchant == null ? "" : merchant, ts});
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

    /** True when a payment with this bank + reference is already stored.
     *  The reference is unique per payment, so this catches the same SMS
     *  imported twice with timestamps minutes apart. */
    public boolean hasRef(String bankCode, String ref) {
        if (ref == null) return false;
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT 1 FROM txns WHERE bankCode=? AND ref=? LIMIT 1",
            new String[]{bankCode, ref});
        boolean hit = c.moveToFirst();
        c.close();
        return hit;
    }

    /** Fill in the reference on a row that was imported before v1.34.
     *  Used when a rescan meets the older copy of the same payment:
     *  same bank/amount/type/merchant within 10 minutes, no ref stored yet.
     *  Returns true when a row was updated (the SMS is then a duplicate). */
    public boolean backfillRef(String bankCode, double amount, String type,
                               String merchant, long ts, String ref) {
        if (ref == null) return false;
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT _key FROM txns WHERE bankCode=? AND ABS(amount-?)<0.005"
                + " AND type=? AND COALESCE(merchant,'')=?"
                + " AND ABS(ts-?)<=600000 AND ref IS NULL LIMIT 1",
            new String[]{bankCode, String.valueOf(amount), type,
                merchant == null ? "" : merchant, String.valueOf(ts)});
        if (!c.moveToFirst()) { c.close(); return false; }
        String key = c.getString(0);
        c.close();
        getWritableDatabase().execSQL("UPDATE txns SET ref=? WHERE _key=?",
            new Object[]{ref, key});
        return true;
    }

    /** Fill in the reference on one known row (re-import of a stored SMS). */
    public void backfillRefByKey(String key, String ref) {
        if (key == null || ref == null) return;
        getWritableDatabase().execSQL(
            "UPDATE txns SET ref=? WHERE _key=? AND ref IS NULL",
            new Object[]{ref, key});
    }

    /**
     * Delete double-counted rows stored before v1.34: same bank, amount,
     * type and merchant within 10 minutes, keeping the earliest. Transfers
     * are excluded (their two legs legitimately share amount and day).
     * Idempotent; returns the number of rows removed.
     */
    public int repairDuplicateTxns() {
        SQLiteDatabase wdb = getWritableDatabase();
        wdb.execSQL("DELETE FROM txns WHERE _key IN ("
            + "SELECT t2._key FROM txns t2 JOIN txns t1"
            + " ON t1._key<>t2._key"
            + " AND t1.bankCode=t2.bankCode"
            + " AND ABS(t1.amount-t2.amount)<0.005"
            + " AND t1.type=t2.type"
            + " AND t1.type IN ('DEBIT','CREDIT','CARD_SPEND')"
            + " AND COALESCE(t1.merchant,'')=COALESCE(t2.merchant,'')"
            + " AND ABS(t1.ts-t2.ts)<=600000"
            + " AND (t1.ts<t2.ts OR (t1.ts=t2.ts AND t1._key<t2._key)))");
        Cursor c = wdb.rawQuery("SELECT changes()", null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
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
        v.put("transferDir", t.transferDir);
        v.put("pairKey", t.pairKey);
        v.put("ref", t.ref);
        getWritableDatabase().insertWithOnConflict("txns", null, v,
            SQLiteDatabase.CONFLICT_IGNORE);
    }

    public List<Transaction> getTxnsSince(long cutoffTs) {
        List<Transaction> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT _key,bankCode,bankName,amount,type,merchant,ts,card4,currency,transferDir,pairKey,ref FROM txns"
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
            t.transferDir = c.getString(9);
            t.pairKey = c.getString(10);
            t.ref = c.getString(11);
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

    /** Total still owed across unpaid dues: total due minus what was already
     *  paid. A minimum-due payment leaves the rest visible until it is paid. */
    public double unpaidDuesTotal() {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT SUM(MAX(totalDue-paid,0)) FROM dues WHERE status IN ('PENDING','OUTSTANDING')",
            null);
        double s = c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : 0;
        c.close();
        return s;
    }

    /** Income (CREDIT) transactions in a range, newest first. */
    public static class IncomeTxn {
        public String merchant;
        public String bankName;
        public String card4;
        public String currency;
        public double amount;
        public long ts;
    }

    public List<IncomeTxn> incomeTxns(long startTs, long endTs) {
        List<IncomeTxn> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT merchant, bankName, card4, currency, amount, ts FROM txns"
            + " WHERE type='CREDIT' AND ts>=? AND ts<=? ORDER BY ts DESC",
            new String[]{String.valueOf(startTs), String.valueOf(endTs)});
        while (c.moveToNext()) {
            IncomeTxn t = new IncomeTxn();
            t.merchant = c.getString(0);
            t.bankName = c.getString(1);
            t.card4 = c.getString(2);
            t.currency = c.getString(3);
            t.amount = c.getDouble(4);
            t.ts = c.getLong(5);
            out.add(t);
        }
        c.close();
        return out;
    }

    /** The earliest unpaid due, for the "due <date>" callout. May be null. */
    public CardDue earliestUnpaidDue() {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT cardKey,bankName,card4,totalDue,minDue,dueTs,status,paid,currency,autoPayMode,lastPaySrc FROM dues"
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
            d.autoPayMode = c.getString(9);
            d.lastPaySrc = c.getString(10);
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

    /**
     * Transactions in a range, newest first. Transfers are excluded here:
     * they live in their own consolidated Transfers section (getTransfers),
     * so the list shows income and expenses only.
     */
    public List<Transaction> getTxnsBetween(long startTs, long endTs) {
        List<Transaction> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT _key,bankCode,bankName,amount,type,merchant,ts,card4,currency,transferDir,pairKey,ref FROM txns"
                + " WHERE ts>=? AND ts<=? AND type!='TRANSFER' ORDER BY ts DESC",
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
            t.transferDir = c.getString(9);
            t.pairKey = c.getString(10);
            t.ref = c.getString(11);
            out.add(t);
        }
        c.close();
        return out;
    }

    /** Inter-bank transfers in a range, newest first, for the consolidated
     *  Transfers section. */
    public List<Transaction> getTransfers(long startTs, long endTs) {
        List<Transaction> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT _key,bankCode,bankName,amount,type,merchant,ts,card4,currency,transferDir,pairKey,ref FROM txns"
                + " WHERE ts>=? AND ts<=? AND type='TRANSFER' ORDER BY ts DESC",
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
            t.transferDir = c.getString(9);
            t.pairKey = c.getString(10);
            t.ref = c.getString(11);
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
        db.execSQL("UPDATE txns SET type='TRANSFER',"
            + " transferDir=CASE WHEN type='CREDIT' THEN 'IN' ELSE 'OUT' END"
            + " WHERE type IN ('CREDIT','DEBIT')"
            + " AND merchant IS NOT NULL AND (" + like + ")",
            ns.toArray(new Object[0]));
        android.database.Cursor c = db.rawQuery("SELECT changes()", null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    /** Mark one transaction as TRANSFER (used by transfer pair-matching).
     *  dir is OUT when money left this account, IN when it arrived; pairKey
     *  links the two legs of the same transfer. */
    public void markTransfer(String key, String dir, String pairKey) {
        getWritableDatabase().execSQL(
            "UPDATE txns SET type='TRANSFER', transferDir=?, pairKey=?"
                + " WHERE _key=?",
            new Object[]{dir, pairKey, key});
    }

    /** "My accounts" registry, set by the UI from SharedPreferences.
     *  Unconfigured (null) means every account counts as the user's own,
     *  preserving the behaviour from before the registry existed. */
    private java.util.Set<String> myAccounts = null;
    private boolean myAccountsConfigured = false;

    public void setMyAccounts(java.util.Set<String> accts, boolean configured) {
        myAccounts = accts;
        myAccountsConfigured = configured;
    }

    public static String acctKey(String bankCode, String card4) {
        return (bankCode == null ? "" : bankCode) + "|"
            + (card4 == null ? "" : card4);
    }

    public boolean isMyAccount(String bankCode, String card4) {
        if (!myAccountsConfigured) return true;
        return myAccounts != null
            && myAccounts.contains(acctKey(bankCode, card4));
    }

    /** Distinct bank accounts seen in SMS (bankCode, bankName, card4),
     *  excluding credit-card spend entries. The user ticks which are theirs. */
    public java.util.List<String[]> discoverAccounts() {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        android.database.Cursor c = getReadableDatabase().rawQuery(
            "SELECT DISTINCT bankCode, bankName, card4 FROM txns"
            + " WHERE type != 'CARD_SPEND' ORDER BY bankName, card4", null);
        while (c.moveToNext()) {
            out.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        }
        c.close();
        return out;
    }

    /** Distinct credit cards seen in SMS (bankCode, bankName, card4),
     *  from card spends and card dues, so the user can tick their own
     *  cards in "My cards". */
    public java.util.List<String[]> discoverCards() {
        java.util.Map<String, String[]> out =
            new java.util.LinkedHashMap<String, String[]>();
        android.database.Cursor c = getReadableDatabase().rawQuery(
            "SELECT DISTINCT bankCode, bankName, card4 FROM txns"
            + " WHERE type='CARD_SPEND' AND card4 IS NOT NULL AND card4<>''",
            null);
        while (c.moveToNext()) {
            String k = c.getString(0) + "|" + c.getString(2);
            if (!out.containsKey(k)) {
                out.put(k, new String[]{c.getString(0), c.getString(1),
                    c.getString(2)});
            }
        }
        c.close();
        android.database.Cursor d = getReadableDatabase().rawQuery(
            "SELECT cardKey, bankName, card4 FROM dues"
            + " WHERE card4 IS NOT NULL AND card4<>''", null);
        while (d.moveToNext()) {
            String key = d.getString(0);
            String code = key != null && key.contains("|")
                ? key.substring(0, key.indexOf('|')) : "";
            String k = code + "|" + d.getString(2);
            if (!out.containsKey(k)) {
                out.put(k, new String[]{code, d.getString(1), d.getString(2)});
            }
        }
        d.close();
        java.util.List<String[]> list =
            new java.util.ArrayList<String[]>(out.values());
        java.util.Collections.sort(list, new java.util.Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                int c = a[1].compareTo(b[1]);
                return c != 0 ? c : a[2].compareTo(b[2]);
            }
        });
        return list;
    }

    /** Delete income rows that are really money credited to one of the
     *  user's own credit cards (bill payments / refunds stored before the
     *  card registry existed). Idempotent; returns the rows removed. */
    public int removeCardAccountCredits(java.util.Set<String> card4s) {
        if (card4s == null || card4s.isEmpty()) return 0;
        StringBuilder ph = new StringBuilder();
        java.util.List<String> args = new java.util.ArrayList<String>();
        for (String c4 : card4s) {
            if (c4 == null || c4.length() != 4) continue;
            if (ph.length() > 0) ph.append(",");
            ph.append("?");
            args.add(c4);
        }
        if (args.isEmpty()) return 0;
        SQLiteDatabase wdb = getWritableDatabase();
        wdb.execSQL("DELETE FROM txns WHERE type='CREDIT' AND card4 IN ("
            + ph + ")", args.toArray(new Object[0]));
        Cursor c = wdb.rawQuery("SELECT changes()", null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
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
        if (!isMyAccount(bankCode, card4)) return null;
        long dayStart = TxnGrouper.dayStart(ts);
        long dayEnd = dayStart + 86400000L;
        String wantTypes = wantCredit ? "'CREDIT'" : "'DEBIT','TRANSFER'";
        android.database.Cursor c = getReadableDatabase().rawQuery(
            "SELECT _key, bankCode, card4 FROM txns"
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
            if (!isMyAccount(c.getString(1), c.getString(2))) continue;
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
            if (!isMyAccount(r[3], r[4])) continue;
            String pair = findPairCandidate(Double.parseDouble(r[1]),
                Long.parseLong(r[2]), r[3], r[4], false, used);
            if (pair != null) {
                // The CREDIT row is the IN leg; the candidate is the OUT leg
                // (a DEBIT row, or an already-marked TRANSFER whose direction
                // is kept when it is not a plain DEBIT).
                String pk = "pair|" + r[0];
                SQLiteDatabase wdb = getWritableDatabase();
                wdb.execSQL("UPDATE txns SET type='TRANSFER',"
                    + " transferDir='IN', pairKey=? WHERE _key=?",
                    new Object[]{pk, r[0]});
                wdb.execSQL("UPDATE txns SET type='TRANSFER',"
                    + " transferDir=CASE WHEN type='DEBIT' THEN 'OUT'"
                    + " ELSE transferDir END, pairKey=? WHERE _key=?",
                    new Object[]{pk, pair});
                used.add(r[0]);
                used.add(pair);
                fixed++;
            }
        }
        return fixed;
    }

    /**
     * Store a statement. A re-import of the *same* statement (same total due
     * and due date, e.g. the once-per-version full rescan) keeps whatever was
     * already paid against it; a statement with a different total or due date
     * is a new billing cycle, so paid resets to 0. The user's auto-pay mode
     * is always preserved.
     */
    public void upsertDue(CardDue d) {
        SQLiteDatabase wdb = getWritableDatabase();
        Cursor c = wdb.rawQuery(
            "SELECT totalDue, dueTs, paid, status, autoPayMode, lastPaySrc"
                + " FROM dues WHERE cardKey=?",
            new String[]{d.cardKey});
        boolean exists = c.moveToFirst();
        double oldPaid = 0;
        String oldStatus = "PENDING";
        String oldMode = "OFF";
        String oldSrc = null;
        boolean sameCycle = false;
        if (exists) {
            double oldTotal = c.getDouble(0);
            long oldDueTs = c.getLong(1);
            oldPaid = c.getDouble(2);
            oldStatus = c.getString(3);
            oldMode = c.getString(4);
            if (oldMode == null) oldMode = "OFF";
            oldSrc = c.getString(5);
            sameCycle = Math.abs(oldTotal - d.totalDue) < 0.005
                && Math.abs(oldDueTs - d.dueTs) < 3 * 86400000L;
        }
        c.close();
        ContentValues v = new ContentValues();
        v.put("cardKey", d.cardKey);
        v.put("bankName", d.bankName);
        v.put("card4", d.card4);
        v.put("totalDue", d.totalDue);
        v.put("minDue", d.minDue);
        v.put("dueTs", d.dueTs);
        v.put("currency", d.currency == null ? "INR" : d.currency);
        v.put("autoPayMode", exists ? oldMode : "OFF");
        if (exists && sameCycle) {
            v.put("paid", oldPaid);
            v.put("status", oldStatus);
            v.put("lastPaySrc", oldSrc);
            wdb.update("dues", v, "cardKey=?", new String[]{d.cardKey});
        } else {
            v.put("paid", 0);
            v.put("status", "PENDING");
            v.putNull("lastPaySrc");
            wdb.insertWithOnConflict("dues", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    /** Manual payment (kept for callers that do not know the source). */
    public boolean markPaid(String cardKey, double paidAmount) {
        return markPaid(cardKey, paidAmount, "MANUAL");
    }

    /**
     * Record a card-bill payment against the matching due. Payments
     * accumulate: paying only the minimum due leaves the rest outstanding
     * and visible. The due is PAID only once paid reaches the total due.
     * src is MANUAL or AUTO (auto-debit SMS). The payment SMS usually names
     * the bank account, not the card, so when no due matches the cardKey
     * exactly the earliest open due of the same bank is used.
     */
    public boolean markPaid(String cardKey, double paidAmount, String src) {
        String target = openDueKey(cardKey);
        if (target == null) {
            String bank = cardKey.contains("|")
                ? cardKey.substring(0, cardKey.indexOf('|')) : cardKey;
            target = openDueKeyForBank(bank);
        }
        if (target == null) return false;
        SQLiteDatabase wdb = getWritableDatabase();
        wdb.execSQL("UPDATE dues SET paid=paid+?, lastPaySrc=? WHERE cardKey=?",
            new Object[]{paidAmount, src, target});
        wdb.execSQL("UPDATE dues SET status='PAID' WHERE cardKey=?"
            + " AND paid>=totalDue-0.005",
            new Object[]{target});
        return true;
    }

    /** CardKey of an open due matching exactly, or null. */
    private String openDueKey(String cardKey) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT cardKey FROM dues WHERE cardKey=?"
                + " AND status IN ('PENDING','OUTSTANDING')",
            new String[]{cardKey});
        String k = c.moveToFirst() ? c.getString(0) : null;
        c.close();
        return k;
    }

    /** CardKey of the earliest open due of a bank, or null. */
    private String openDueKeyForBank(String bank) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT cardKey FROM dues WHERE cardKey LIKE ?"
                + " AND status IN ('PENDING','OUTSTANDING')"
                + " ORDER BY dueTs ASC LIMIT 1",
            new String[]{bank + "|%"});
        String k = c.moveToFirst() ? c.getString(0) : null;
        c.close();
        return k;
    }

    /** Set the user's auto-pay mode for a card: OFF, TOTAL or MINIMUM. */
    public void setAutoPayMode(String cardKey, String mode) {
        if (mode == null
            || (!"TOTAL".equals(mode) && !"MINIMUM".equals(mode))) {
            mode = "OFF";
        }
        getWritableDatabase().execSQL(
            "UPDATE dues SET autoPayMode=? WHERE cardKey=?",
            new Object[]{mode, cardKey});
    }

    public void refreshOverdue(long now) {
        ContentValues v = new ContentValues();
        v.put("status", "OUTSTANDING");
        getWritableDatabase().update("dues", v,
            "status='PENDING' AND dueTs<?", new String[]{String.valueOf(now)});
    }

    /** One bank account's last-known balance, from the newest SMS that
     *  carried one. The "as of" date is the SMS date: balances only move
     *  when a bank SMS shows one (UPI spends carry none). */
    public static class Balance {
        public String bankCode;
        public String bankName;
        public String acct4;
        public double balance;
        public long ts;
        public String currency;
    }

    /** Record a balance, keeping only the newest per account. A rescan
     *  re-reads old SMS, so an older reading must never overwrite a
     *  newer one. */
    public void upsertBalance(String bankCode, String bankName, String acct4,
                              double balance, long ts, String currency) {
        if (bankCode == null) return;
        if (acct4 == null) acct4 = "";
        SQLiteDatabase wdb = getWritableDatabase();
        Cursor c = wdb.rawQuery(
            "SELECT ts FROM balances WHERE bankCode=? AND acct4=?",
            new String[]{bankCode, acct4});
        boolean exists = c.moveToFirst();
        long oldTs = exists ? c.getLong(0) : 0;
        c.close();
        if (exists && ts < oldTs) return;
        ContentValues v = new ContentValues();
        v.put("bankCode", bankCode);
        v.put("acct4", acct4);
        v.put("bankName", bankName == null ? bankCode : bankName);
        v.put("balance", balance);
        v.put("ts", ts);
        v.put("currency", currency == null ? "INR" : currency);
        wdb.insertWithOnConflict("balances", null, v,
            SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Last-known balances, newest activity first. */
    public List<Balance> getBalances() {
        List<Balance> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT bankCode, bankName, acct4, balance, ts, currency"
                + " FROM balances ORDER BY bankName, acct4", null);
        while (c.moveToNext()) {
            Balance b = new Balance();
            b.bankCode = c.getString(0);
            b.bankName = c.getString(1);
            b.acct4 = c.getString(2);
            b.balance = c.getDouble(3);
            b.ts = c.getLong(4);
            b.currency = c.getString(5);
            out.add(b);
        }
        c.close();
        return out;
    }

    public List<CardDue> getDues() {
        List<CardDue> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT cardKey,bankName,card4,totalDue,minDue,dueTs,status,paid,currency,autoPayMode,lastPaySrc FROM dues"
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
            d.autoPayMode = c.getString(9);
            d.lastPaySrc = c.getString(10);
            out.add(d);
        }
        c.close();
        return out;
    }
}
