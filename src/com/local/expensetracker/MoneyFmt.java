/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Currency-aware amount formatting. INR keeps the existing 0-decimal style;
 *  foreign currencies show 2 decimals. Aggregates use the dominant currency
 *  (single-currency users, the expected case, are unaffected). */
public class MoneyFmt {

    public static String symbol(String currency) {
        if ("QAR".equals(currency)) return "QR ";
        if ("AUD".equals(currency)) return "A$";
        if ("USD".equals(currency)) return "US$";
        return "\u20B9";
    }

    public static String money(double amount, String currency) {
        if ("INR".equals(currency) || currency == null) {
            return "\u20B9" + String.format(Locale.US, "%,.0f", amount);
        }
        return symbol(currency) + String.format(Locale.US, "%,.2f", amount);
    }

    /** Most common currency in the list; INR when empty/tied. */
    public static String dominant(List<Transaction> txns) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (Transaction t : txns) {
            String c = t.currency == null ? "INR" : t.currency;
            Integer n = counts.get(c);
            counts.put(c, (n == null ? 0 : n) + 1);
        }
        String best = "INR";
        int bestN = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }
}
