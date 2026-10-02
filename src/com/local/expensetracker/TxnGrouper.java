package com.local.expensetracker;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Groups transactions (newest-first) into day buckets with headers.
 * Pure Java: no Android dependencies, unit-testable on the JVM.
 */
public class TxnGrouper {

    /** Header row shown above one day's transactions. */
    public static class DayHeader {
        public final String label;
        public final double spent;   // DEBIT + CARD_SPEND total for the day
        public final int count;
        public DayHeader(String label, double spent, int count) {
            this.label = label;
            this.spent = spent;
            this.count = count;
        }
    }

    /** Interleaves DayHeaders and Transactions, newest day first. */
    public static List<Object> groupByDay(List<Transaction> txns) {
        Map<Long, List<Transaction>> buckets = new LinkedHashMap<Long, List<Transaction>>();
        for (Transaction t : txns) {
            long day = dayStart(t.ts);
            List<Transaction> b = buckets.get(day);
            if (b == null) {
                b = new ArrayList<Transaction>();
                buckets.put(day, b);
            }
            b.add(t);
        }
        List<Object> out = new ArrayList<Object>();
        long today = dayStart(System.currentTimeMillis());
        for (Map.Entry<Long, List<Transaction>> e : buckets.entrySet()) {
            double spent = 0;
            for (Transaction t : e.getValue()) {
                if ("DEBIT".equals(t.type) || "CARD_SPEND".equals(t.type)) {
                    spent += t.amount;
                }
            }
            out.add(new DayHeader(dayLabel(e.getKey(), today), spent,
                    e.getValue().size()));
            out.addAll(e.getValue());
        }
        return out;
    }

    public static long dayStart(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String dayLabel(long dayStart, long todayStart) {
        if (dayStart == todayStart) return "Today";
        if (dayStart == todayStart - 86400000L) return "Yesterday";
        return new SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
                .format(new Date(dayStart));
    }
}
