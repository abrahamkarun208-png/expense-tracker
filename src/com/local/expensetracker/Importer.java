/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

/** Shared import logic: parse one SMS and store the result. Used by the
 *  activity (bulk import) and the receiver (live incoming SMS). */
public class Importer {

    public static void importOne(DbHelper db, String address, String body, long smsTs) {
        SmsParser.Result r = SmsParser.parse(address, body, smsTs);
        if (r == null) return;

        if (r.isStatement) {
            CardDue d = new CardDue();
            d.cardKey = r.cardKey;
            d.bankName = r.bankName;
            d.card4 = r.card4;
            d.totalDue = r.totalDue;
            d.minDue = r.minDue;
            d.dueTs = r.dueTs;
            d.currency = r.currency;
            db.upsertDue(d);
            return;
        }
        if (r.isPayment) {
            db.markPaid(r.cardKey, r.amount);
            return;
        }
        if (r.isTxn) {
            String key = "sms|" + address + "|" + smsTs + "|" + body.hashCode();
            if (db.txnExists(key)) return;
            Transaction t = new Transaction();
            t.key = key;
            t.bankCode = r.bankCode;
            t.bankName = r.bankName;
            t.amount = r.amount;
            t.type = r.type;
            t.merchant = r.merchant == null ? "" : r.merchant;
            t.ts = smsTs;
            t.card4 = r.card4;
            t.currency = r.currency == null ? "INR" : r.currency;
            // Nameless self-transfer: same amount, same day, different account,
            // opposite direction (some banks omit the sender name entirely).
            if (("CREDIT".equals(t.type) || "DEBIT".equals(t.type))
                    && t.amount >= 1000) {
                String pair = db.findPairCandidate(t.amount, t.ts, t.bankCode,
                    t.card4, "DEBIT".equals(t.type), null);
                if (pair != null) {
                    t.type = "TRANSFER";
                    db.markTransfer(pair);
                }
            }
            if (db.hasNearDuplicate(t.bankCode, t.amount, t.type, t.merchant, t.ts)) {
                // Already imported by an older version: fill in account digits
                // the old parser missed, then skip.
                db.backfillCard4(t.bankCode, t.amount, t.type, t.merchant, t.ts,
                    t.card4);
                return;
            }
            db.insertTxn(t);
        }
    }
}
