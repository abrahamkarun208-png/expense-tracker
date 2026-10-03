/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Indian bank SMS: transactions, card statements and card payments. */
public class SmsParser {

    public static class Result {
        public boolean isTxn;
        public boolean isStatement;
        public boolean isPayment;
        public String bankCode = "";
        public String bankName = "";
        public double amount;
        public String type = "";          // DEBIT, CREDIT, TRANSFER, CARD_SPEND
        public String merchant = "";
        public String card4 = "";
        public String cardKey = "";
        public double totalDue;
        public double minDue;
        public long dueTs;
    }

    // {code, full name, sender-id fragments...}
    private static final String[][] BANKS = {
        {"HDFC", "HDFC Bank", "HDFCBK", "HDFCBNK"},
        {"SBI", "State Bank of India", "SBIBNK", "SBIINB", "SBIPSG", "SBIYONO", "SBISMS"},
        {"ICICI", "ICICI Bank", "ICICIB", "ICICIBK"},
        {"AXIS", "Axis Bank", "AXISBK", "AXISBNK"},
        {"KOTAK", "Kotak Mahindra", "KOTAKB", "KOTAK"},
        {"PNB", "Punjab National Bank", "PNBSMS", "PNBBNK"},
        {"BOB", "Bank of Baroda", "BOBTXN", "BAROD", "BOBBNK"},
        {"CANARA", "Canara Bank", "CANBNK"},
        {"YES", "Yes Bank", "YESBNK"},
        {"IDFC", "IDFC First Bank", "IDFCBK"},
        {"IDBI", "IDBI Bank", "IDBIBK"},
        {"UNION", "Union Bank", "UNIONB"},
        {"INDUSIND", "IndusInd Bank", "INDUSB", "INDUS"},
        {"FEDERAL", "Federal Bank", "FEDBNK"},
        {"IOB", "Indian Overseas Bank", "IOBCHN", "IOBBNK"},
        {"CUB", "City Union Bank", "CUBMBL"},
        {"RBL", "RBL Bank", "RBLBNK", "RBLCRD"},
        {"AUBANK", "AU Small Finance", "AUBANK"},
    };

    private static final Pattern AMOUNT =
        Pattern.compile("(?i)(?:rs\\.?|inr|\\u20B9)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");
    private static final Pattern CARD4 =
        Pattern.compile("(?i)(?:ending|ends?\\s+with|xx+|x{4,}|card\\s*no\\.?\\s*(?:ending\\s*)?)\\s*(\\d{4})");
    private static final Pattern MERCHANT_KW =
        Pattern.compile("(?i)\\b(at|to|towards|from)\\b");

    public static Result parse(String address, String body, long smsTs) {
        if (body == null || body.length() < 10) return null;
        String addr = address == null ? "" : address.toUpperCase(Locale.US);
        String low = body.toLowerCase(Locale.US);

        String[] bank = detectBank(addr, body);
        if (bank == null) return null;

        Result r = new Result();
        r.bankCode = bank[0];
        r.bankName = bank[1];

        double firstAmt = firstAmount(body);

        // 1) Card statement: "Total Amt Due ... Min Amt Due ... due by <date>"
        if (low.contains("total") && low.contains("due")
                && (low.contains("statement") || low.contains("min"))) {
            double total = extract(body,
                "(?i)total\\s+(?:amt\\.?|amount)?\\s*due\\s*:?\\s*(?:rs\\.?|inr|\\u20B9)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");
            double min = extract(body,
                "(?i)min(?:imum)?\\s+(?:amt\\.?|amount)?\\s*due\\s*:?\\s*(?:rs\\.?|inr|\\u20B9)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");
            if (total > 0) {
                r.isStatement = true;
                r.totalDue = total;
                r.minDue = min;
                r.card4 = extractCard4(body);
                r.cardKey = r.bankCode + "|" + (r.card4.isEmpty() ? "CARD" : r.card4);
                r.dueTs = extractDueDate(body, smsTs);
                return r;
            }
        }

        // 2) Card payment receipt: "payment of Rs X received/credited", "thank you for your payment"
        if ((low.contains("payment") && (low.contains("received") || low.contains("credited")
                    || low.contains("thank")))
                || low.contains("payment received")) {
            if (firstAmt > 0) {
                r.isPayment = true;
                r.amount = firstAmt;
                r.card4 = extractCard4(body);
                r.cardKey = r.bankCode + "|" + (r.card4.isEmpty() ? "CARD" : r.card4);
                return r;
            }
        }

        // 3) Transaction
        if (firstAmt <= 0) return null;
        String type = classify(low, body, addr);
        if (type == null) return null;

        r.isTxn = true;
        r.amount = firstAmt;
        r.type = type;
        r.merchant = extractMerchant(body);
        if (r.merchant.isEmpty() && "TRANSFER".equals(type)) r.merchant = "Own account";
        r.card4 = extractCard4(body);
        return r;
    }

    private static String[] detectBank(String addr, String body) {
        String up = body.toUpperCase(Locale.US);
        for (String[] b : BANKS) {
            for (int i = 2; i < b.length; i++) {
                if (addr.contains(b[i])) return b;
            }
            if (addr.contains(b[0]) && b[0].length() > 3) return b;
        }
        for (String[] b : BANKS) {
            if (up.contains(b[1].toUpperCase(Locale.US))) return b;
        }
        return null;
    }

    private static double firstAmount(String body) {
        Matcher m = AMOUNT.matcher(body);
        if (m.find()) return parseNum(m.group(1));
        return 0;
    }

    private static double extract(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        if (m.find()) return parseNum(m.group(1));
        return 0;
    }

    private static double parseNum(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String extractCard4(String body) {
        // Never treat a bank *account* number ("A/c xx1234") as a card number.
        String b = body.replaceAll("(?i)\\ba\\s*/?\\s*c\\.?\\s*(?:xx|x{2,})\\s*\\d{4}", " ");
        Matcher m = CARD4.matcher(b);
        if (m.find()) return m.group(1);
        return "";
    }

    private static String classify(String low, String body, String addr) {
        boolean cardCtx = low.contains("credit card") || !extractCard4(body).isEmpty();
        boolean upiCtx = low.contains("upi");
        boolean debitW = low.contains("debited") || low.contains("spent") || low.contains("purchase")
            || low.contains("paid") || low.contains("withdrawn") || low.contains("charged")
            || low.contains("txn of");
        boolean creditW = low.contains("credited") || low.contains("received")
            || low.contains("deposited") || low.contains("refund") || low.contains("cashback");
        boolean transferW = low.contains("own account") || low.contains("self")
            || low.contains("fund transfer") || low.contains("transferred to");

        if (cardCtx && debitW) return "CARD_SPEND";
        if (transferW && (debitW || low.contains("transfer"))) return "TRANSFER";
        // HDFC UPI debit: "Sent Rs.1860.00 From HDFC Bank A/C *9066 To ..."
        // (anchored at the start so "sent by ..." in credit SMS can't misfire)
        if (low.trim().startsWith("sent ") && !creditW) return "DEBIT";
        if (debitW && !creditW) return "DEBIT";
        if (creditW && !debitW) return "CREDIT";
        // UPI "transaction of Rs X ... is successful" (money sent, no explicit debit verb)
        if (upiCtx && (low.contains("transaction of") || low.contains("payment of"))
                && (low.contains("successful") || low.contains("success"))
                && !creditW) return "DEBIT";
        if (debitW) return "DEBIT";
        if (creditW) return "CREDIT";
        return null;
    }

    private static String extractMerchant(String body) {
        // Multi-line bank SMS: treat newlines as spaces so cut-words work.
        body = body.replace('\n', ' ').replace('\r', ' ');
        Matcher k = MERCHANT_KW.matcher(body);
        while (k.find()) {
            int start = k.end();
            int end = body.length();
            Matcher k2 = MERCHANT_KW.matcher(body);
            if (k2.find(start)) end = k2.start();
            String s = body.substring(start, Math.min(end, start + 44)).trim()
                    .replaceAll("^[\\s:;,-]+", "");
            if (s.isEmpty() || !Character.isLetterOrDigit(s.charAt(0))) continue;
            String l = s.toLowerCase(Locale.US);
            // Skip "credited to your A/c ...", "from HDFC Bank ..." etc.
            if (l.startsWith("your ") || l.startsWith("my ") || l.startsWith("our ")
                    || l.startsWith("the ") || l.contains(" bank") || l.contains("a/c")
                    || l.contains("acct")) {
                continue;
            }
            int best = s.length();
            for (String cut : new String[]{" on ", " via ", " is ", " was ", " has ",
                                           " ref ", " txn ", ","}) {
                int i = l.indexOf(cut);
                if (i > 1 && i < best) best = i;
            }
            s = s.substring(0, best).trim();
            if (s.length() > 34) s = s.substring(0, 34).trim();
            // Drop noise prefixes like "VPA " / "UPI/".
            s = s.replaceFirst("(?i)^vpa\\s+", "").replaceFirst("(?i)^upi/", "");
            if (s.length() >= 2) return s;
        }
        return "";
    }

    private static long extractDueDate(String body, long smsTs) {
        String[] regs = {
            "(?i)(?:due\\s+(?:by|date|on)|payment\\s+due\\s+date)\\s*:?\\s*(\\d{1,2}[-/][A-Za-z]{3,9}[-/]\\d{2,4})",
            "(?i)(?:due\\s+(?:by|date|on)|payment\\s+due\\s+date)\\s*:?\\s*(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})",
            "(?i)(?:due\\s+(?:by|date|on)|payment\\s+due\\s+date)\\s*:?\\s*([A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})",
        };
        String[] dashFmts = {"dd-MMM-yyyy", "dd-MM-yyyy"};
        String[] dashFmts2 = {"dd-MMM-yy", "dd-MM-yy"};
        String[] mdyFmts = {"MMM dd yyyy", "MMM dd, yyyy"};
        for (String reg : regs) {
            Matcher m = Pattern.compile(reg).matcher(body);
            if (m.find()) {
                String ds = m.group(1).replace("/", "-").replace(",", "").trim()
                        .replaceAll("\\s+", " ");
                // Pick 2- vs 4-digit year formats from the actual year token,
                // so "26" can never be read as the year 26 AD.
                String[] parts = ds.split("[- ]");
                String yearTok = parts[parts.length - 1];
                String[][] ordered;
                if (yearTok.length() == 2) {
                    ordered = new String[][]{dashFmts2, mdyFmts};
                } else if (yearTok.length() == 4) {
                    ordered = new String[][]{dashFmts, mdyFmts};
                } else {
                    ordered = new String[][]{dashFmts, dashFmts2, mdyFmts};
                }
                for (String[] fmts : ordered) {
                    for (String f : fmts) {
                        try {
                            SimpleDateFormat sdf = new SimpleDateFormat(f, Locale.ENGLISH);
                            sdf.setLenient(false);
                            Date d = sdf.parse(ds);
                            if (d != null) return d.getTime();
                        } catch (ParseException ignored) {}
                    }
                }
            }
        }
        // Fallback: 20 days after the statement SMS.
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(smsTs);
        c.add(Calendar.DAY_OF_YEAR, 20);
        return c.getTimeInMillis();
    }

    /** All amounts in a message (for future use / debugging). */
    public static List<Double> allAmounts(String body) {
        List<Double> out = new ArrayList<>();
        Matcher m = AMOUNT.matcher(body);
        while (m.find()) out.add(parseNum(m.group(1)));
        return out;
    }
}
