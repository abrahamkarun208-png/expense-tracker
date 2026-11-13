/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consolidates inter-bank transfers for the Transfers section: the two legs
 * of one transfer (same day, same amount, different accounts) become a
 * single entry, "HDFC ....1234 -> SBI ....5678". Legs that never paired
 * (one side missing from SMS) stay as single entries. Pure Java: no Android
 * dependencies, unit-testable on the JVM.
 */
public class TransferGrouper {

    /** One consolidated transfer: a pair of legs, or a single unpaired leg. */
    public static class Entry {
        public double amount;
        public String currency;
        public long ts;            // latest leg, for ordering
        public String fromBank;    // account money left (may be null)
        public String from4;
        public String toBank;      // account money arrived at (may be null)
        public String to4;
        public boolean paired;
        /** True when from -> to is known from the legs' directions. */
        public boolean directionKnown;
    }

    public static List<Entry> consolidate(List<Transaction> transfers) {
        // Group legs by day + amount (newest first, insertion order kept).
        Map<String, List<Transaction>> groups =
            new LinkedHashMap<String, List<Transaction>>();
        for (Transaction t : transfers) {
            String k = TxnGrouper.dayStart(t.ts) + "|"
                + Math.round(t.amount * 100);
            List<Transaction> g = groups.get(k);
            if (g == null) {
                g = new ArrayList<Transaction>();
                groups.put(k, g);
            }
            g.add(t);
        }
        List<Entry> out = new ArrayList<Entry>();
        for (List<Transaction> g : groups.values()) {
            boolean[] used = new boolean[g.size()];
            for (int i = 0; i < g.size(); i++) {
                if (used[i]) continue;
                Transaction a = g.get(i);
                int mate = -1;
                for (int j = i + 1; j < g.size(); j++) {
                    if (used[j]) continue;
                    if (!sameAccount(a, g.get(j))) { mate = j; break; }
                }
                if (mate >= 0) {
                    used[i] = true;
                    used[mate] = true;
                    out.add(pairedEntry(a, g.get(mate)));
                } else {
                    used[i] = true;
                    out.add(singleEntry(a));
                }
            }
        }
        return out;
    }

    private static boolean sameAccount(Transaction a, Transaction b) {
        String ka = (a.bankCode == null ? "" : a.bankCode) + "|"
            + (a.card4 == null ? "" : a.card4);
        String kb = (b.bankCode == null ? "" : b.bankCode) + "|"
            + (b.card4 == null ? "" : b.card4);
        return ka.equals(kb);
    }

    private static Entry pairedEntry(Transaction a, Transaction b) {
        Entry e = new Entry();
        e.amount = a.amount;
        e.currency = a.currency;
        e.ts = Math.max(a.ts, b.ts);
        e.paired = true;
        Transaction outLeg = "OUT".equals(a.transferDir) ? a
            : "OUT".equals(b.transferDir) ? b : null;
        Transaction inLeg = "IN".equals(a.transferDir) ? a
            : "IN".equals(b.transferDir) ? b : null;
        if (outLeg != null && inLeg != null) {
            e.directionKnown = true;
            e.fromBank = outLeg.bankName;
            e.from4 = outLeg.card4;
            e.toBank = inLeg.bankName;
            e.to4 = inLeg.card4;
        } else {
            // Direction was never recorded: order by time, no arrow claim.
            Transaction first = a.ts <= b.ts ? a : b;
            Transaction second = first == a ? b : a;
            e.directionKnown = false;
            e.fromBank = first.bankName;
            e.from4 = first.card4;
            e.toBank = second.bankName;
            e.to4 = second.card4;
        }
        return e;
    }

    private static Entry singleEntry(Transaction t) {
        Entry e = new Entry();
        e.amount = t.amount;
        e.currency = t.currency;
        e.ts = t.ts;
        e.paired = false;
        if ("OUT".equals(t.transferDir)) {
            e.directionKnown = true;
            e.fromBank = t.bankName;
            e.from4 = t.card4;
        } else if ("IN".equals(t.transferDir)) {
            e.directionKnown = true;
            e.toBank = t.bankName;
            e.to4 = t.card4;
        } else {
            e.directionKnown = false;
            e.fromBank = t.bankName;
            e.from4 = t.card4;
        }
        return e;
    }

    /** "HDFC ....1234 -> SBI ....5678", or "<->" when the direction was
     *  never recorded. Single legs show the one account known. */
    public static String route(Entry e) {
        String from = acct(e.fromBank, e.from4);
        String to = acct(e.toBank, e.to4);
        if (e.paired) {
            return from + (e.directionKnown ? " \u2192 " : " \u2194 ") + to;
        }
        if (e.toBank != null) return "Transfer to " + to;
        if (e.fromBank != null) return "Transfer from " + from;
        return "Transfer";
    }

    private static String acct(String bank, String four) {
        String b = bank == null || bank.isEmpty() ? "Account" : bank;
        if (four == null || four.isEmpty()) return b;
        return b + " \u2022\u2022\u2022\u2022 " + four;
    }
}
