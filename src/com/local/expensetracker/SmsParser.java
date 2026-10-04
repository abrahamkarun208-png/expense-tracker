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
        public String currency = "INR";   // INR, QAR, AUD, USD (from the amount token)
        public double totalDue;
        public double minDue;
        public long dueTs;
    }

    // {code, full name, sender-id fragments...}
    private static final String[][] BANKS = {
        {"HDFC", "HDFC Bank", "HDFCBK", "HDFCBNK", "HDFCBAN", "PAYZAP", "HDFCCC", "HDFCDC"},
        {"SBI", "State Bank of India", "SBIBNK", "SBIINB", "SBIPSG", "SBIYONO", "SBISMS", "SBIUPI", "SBICRD", "ATMSBI", "CBSSBI"},
        {"ICICI", "ICICI Bank", "ICICIB", "ICICIBK", "ICICIT", "ICBANK"},
        {"AXIS", "Axis Bank", "AXISBK", "AXISBNK", "AXIS", "AXSFIN"},
        {"KOTAK", "Kotak Mahindra", "KOTAKB", "KOTAK", "KTKREM"},
        {"PNB", "Punjab National Bank", "PNBSMS", "PNBBNK", "PNBINB"},
        {"BOB", "Bank of Baroda", "BOBTXN", "BAROD", "BOBBNK", "BARBSM", "BOBSMS", "BOBIBK"},
        {"CANARA", "Canara Bank", "CANBNK", "CNRBNK", "CAANBK"},
        {"YES", "Yes Bank", "YESBNK", "YESBK", "YESBANK"},
        {"IDFC", "IDFC First Bank", "IDFCBK", "IDFCFB", "IDFC"},
        {"IDBI", "IDBI Bank", "IDBIBK", "IDBIBANK"},
        {"UNION", "Union Bank", "UNIONB", "UBISMS", "UNION"},
        {"INDUSIND", "IndusInd Bank", "INDUSB", "INDUS", "INDBNK"},
        {"FEDERAL", "Federal Bank", "FEDBNK", "FEDBK", "FEDSMS"},
        {"IOB", "Indian Overseas Bank", "IOBCHN", "IOBBNK", "IOB"},
        {"CUB", "City Union Bank", "CUBMBL", "CUBSMS", "CUBBNK", "CUBANK", "CUBLTD"},
        {"RBL", "RBL Bank", "RBLBNK", "RBLCRD", "RBLBANK"},
        {"AUBANK", "AU Small Finance", "AUBANK"},
        {"SC", "Standard Chartered", "SCBANK", "STANCHART"},
        {"BOI", "Bank of India", "BOIIND", "BOIBNK", "BOISML"},
        {"CBI", "Central Bank of India", "CENTBK", "CBOI"},
        {"INDIAN", "Indian Bank", "INDBKS"},
        {"UCO", "UCO Bank", "UCOBNK", "UCOBANK"},
        {"SIB", "South Indian Bank", "SIBSMS", "SIBBANK"},
        {"KBL", "Karnataka Bank", "KBLBNK", "KTKBANK", "KARBANK", "KARNATABANK"},
        {"BANDHAN", "Bandhan Bank", "BDNSMS", "BANDHN", "BANDHAN"},
        {"EQUITAS", "Equitas Small Finance", "EQUTAS", "EQUITA"},
        {"ESAF", "ESAF Small Finance Bank", "ESAFSF", "ESAF"},
        {"IPPB", "India Post Payments Bank", "IPBMSG", "IPPB"},
        {"DHANLAXMI", "Dhanlaxmi Bank", "DHANBK", "DHANLAXMI"},
        {"HSBC", "HSBC India", "HSBC", "HSBCIN"},
        {"NSDLPB", "NSDL Payments Bank", "NSDLPB"},
        {"SLICE", "Slice Small Finance Bank", "SLCBNK", "SLICE"},
        {"JKBANK", "Jammu & Kashmir Bank", "JKBANK"},
        {"JIOPB", "Jio Payments Bank", "JIOPBS"},
        {"MAHABK", "Bank of Maharashtra", "MAHABK"},
        {"PSB", "Punjab & Sind Bank", "PSBANK", "PSBALRT"},
        {"DCB", "DCB Bank", "DCBANK"},
        {"SBM", "SBM Bank", "SBMBANK"},
        {"UJJIVAN", "Ujjivan Small Finance Bank", "UJJIVAN"},
        {"TMB", "Tamilnad Mercantile Bank", "TMBANK"},
        {"KVB", "Karur Vysya Bank", "KVBANK", "KVBUPI"},
        {"CSB", "CSB Bank", "CSFBNK"},
        {"AIRTELPB", "Airtel Payments Bank", "AIRBNK"},
        // Qatar (sender IDs inferred from research 2026-10-05; formats unverified)
        {"QNB", "QNB Group", "QNB", "QNBALERT", "QNB-ALERT"},
        {"CBQ", "Commercial Bank", "CBQ", "CBQAT", "CB-ALERT"},
        {"DOHABANK", "Doha Bank", "DOHABANK", "DOHA-BANK", "DOBANK", "DBANK"},
        {"ALRAYAN", "AlRayan Bank", "ALRAYAN", "RAYANBANK"},
        {"QIB", "Qatar Islamic Bank", "QIB", "QIB-ALERT"},
        {"DUKHAN", "Dukhan Bank", "DUKHAN", "DUKHANBANK", "BARWA"},
        {"AHLIBANK", "Ahli Bank", "AHLIBANK", "AHLI"},
        {"QIIB", "Qatar International Islamic Bank", "QIIB"},
        {"MASHREQ", "Mashreq", "MASHREQ"},
        // Australia: banks mostly use app push, not SMS; ING (verified sender)
        // offers opt-in per-transaction SMS. Others are low-confidence.
        {"INGAU", "ING Australia", "ING", "INGOTP"},
        {"COMMBANK", "Commonwealth Bank", "COMMBANK"},
        {"WESTPAC", "Westpac", "WESTPAC"},
        {"ANZ", "ANZ", "ANZ"},
        {"NAB", "NAB", "NAB"},
        {"MACQUARIE", "Macquarie Bank", "MACQUARIE"},
        {"BENDIGO", "Bendigo Bank", "BENDIGO"},
        {"SUNCORP", "Suncorp Bank", "SUNCORP"},
        {"BOQ", "Bank of Queensland", "BOQ"},
        {"UBANK", "UBank", "UBANK"},
    };

    // Group 1 = currency token (rs/inr/usd/qar/qr/aud/INR-symbol/$), group 2 = number.
    private static final Pattern AMOUNT =
        Pattern.compile("(?i)(rs\\.?|inr|usd|qar|qr|aud|\\u20B9|\\$)\\s*:?\\s*([0-9,]+(?:\\.[0-9]{1,2})?|\\.[0-9]{1,2})");
    private static final Pattern CARD4 =
        Pattern.compile("(?i)(?:ending|ends?\\s+with|xx+|\\*+|x{4,}|card\\s*no\\.?\\s*(?:ending\\s*)?|card\\s*\\*+|card\\w*\\s+x)\\s*(\\d{4})");
    private static final Pattern MERCHANT_KW =
        Pattern.compile("(?i)\\b(at|to|towards|from)\\b");
    // "Dr."/"Cr." abbreviations (BOB, Canara, AU use these instead of debited/credited).
    private static final Pattern DR_ABBR = Pattern.compile("(?i)\\bdr\\b");
    private static final Pattern CR_ABBR = Pattern.compile("(?i)\\bcr\\b");
    // Direction anchored on the user's own account, for messages that name
    // both sides ("Your a/c ... is debited ... and credited to a/c ...").
    // The verb usually follows within ~50 chars ("a/c no." itself has a dot,
    // so a no-dot window would break).
    private static final Pattern OWN_CREDITED =
        Pattern.compile("(?i)your\\s+a/?c.{0,50}?credited");
    private static final Pattern OWN_DEBITED =
        Pattern.compile("(?i)your\\s+a/?c.{0,50}?debited");

    public static Result parse(String address, String body, long smsTs) {
        if (body == null || body.length() < 10) return null;
        String addr = address == null ? "" : address.toUpperCase(Locale.US);
        String low = body.toLowerCase(Locale.US);

        String[] bank = detectBank(addr, body);
        if (bank == null) return null;
        // "PayID" never sends SMS (verified, AU research) - anything claiming
        // to be from PayID is a scam marker, never a transaction.
        if (addr.contains("PAYID")) return null;

        Result r = new Result();
        r.bankCode = bank[0];
        r.bankName = bank[1];

        double firstAmt = firstAmount(body);

        // 1) Card statement: "Total Amt Due ... Min Amt Due ... due by <date>"
        if (low.contains("total") && low.contains("due")
                && (low.contains("statement") || low.contains("min"))) {
            String stmtCur = extractCurrency(body,
                "(?i)total\\s+(?:amt\\.?|amount)?\\s*due\\s*:?\\s*(rs\\.?|inr|usd|qar|qr|aud|\\u20B9|\\$)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");
            double total = extract(body,
                "(?i)total\\s+(?:amt\\.?|amount)?\\s*due\\s*:?\\s*(?:rs\\.?|inr|usd|qar|qr|aud|\\u20B9|\\$)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");
            double min = extract(body,
                "(?i)min(?:imum)?\\s+(?:amt\\.?|amount)?\\s*due\\s*:?\\s*(?:rs\\.?|inr|usd|qar|qr|aud|\\u20B9|\\$)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");
            if (total > 0) {
                r.isStatement = true;
                r.totalDue = total;
                r.minDue = min;
                r.currency = stmtCur;
                r.card4 = extractCard4(body);
                r.cardKey = r.bankCode + "|" + (r.card4.isEmpty() ? "CARD" : r.card4);
                r.dueTs = extractDueDate(body, smsTs);
                return r;
            }
        }

        // 2) Card payment receipt: "payment of Rs X received/credited", "thank you for your payment"
        if ((low.contains("payment") && (low.contains("received") || low.contains("credited")
                    || low.contains("thank") || low.contains("made to")))
                || low.contains("payment received")) {
            if (firstAmt > 0) {
                r.isPayment = true;
                r.amount = firstAmt;
                r.currency = firstCurrency(body);
                r.card4 = extractCard4(body);
                r.cardKey = r.bankCode + "|" + (r.card4.isEmpty() ? "CARD" : r.card4);
                return r;
            }
        }

        // 2b) Known non-transaction alerts that wear transaction shapes:
        // bill-due reminders, mandate creations, lien/ASBA blocks, failed or
        // declined transactions, and beneficiary confirmations of an outgoing
        // transfer (the debit SMS already covered those).
        if (isNonTxnAlert(low)) return null;

        // 3) Transaction
        if (firstAmt <= 0) return null;
        String type = classify(low, body, addr);
        if (type == null) return null;

        r.isTxn = true;
        r.amount = firstAmt;
        r.currency = firstCurrency(body);
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
        if (m.find()) return parseNum(m.group(2));
        return 0;
    }

    /** Currency code (INR/QAR/AUD/USD) from the first amount token. */
    private static String firstCurrency(String body) {
        Matcher m = AMOUNT.matcher(body);
        if (m.find()) return currencyOf(m.group(1));
        return "INR";
    }

    private static String currencyOf(String token) {
        String t = token.toLowerCase(Locale.US);
        if (t.startsWith("qar") || t.equals("qr")) return "QAR";
        if (t.equals("aud") || t.equals("$")) return "AUD";
        if (t.equals("usd")) return "USD";
        return "INR";
    }

    private static double extract(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        if (m.find()) return parseNum(m.group(1));
        return 0;
    }

    /** Currency from a two-group (currency, number) amount regex; INR default. */
    private static String extractCurrency(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        if (m.find() && m.group(1) != null) return currencyOf(m.group(1));
        return "INR";
    }

    private static double parseNum(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String extractCard4(String body) {
        // Never treat a bank *account* number ("A/c xx1234", "A/C *XX0000",
        // "Acct XX1234", "A/c no. XX1234") as a card number.
        String b = body.replaceAll(
            "(?i)\\b(?:a\\s*/?\\s*c|acct|account)\\.?\\s*(?:no\\.?\\s*)?\\*?\\s*(?:xx|x{2,})\\s*\\d{4}",
            " ");
        Matcher m = CARD4.matcher(b);
        if (m.find()) return m.group(1);
        return "";
    }

    private static String classify(String low, String body, String addr) {
        boolean cardCtx = low.contains("credit card") || low.contains("debit card")
            || !extractCard4(body).isEmpty()
            || (low.contains("card") && (low.contains("spent") || low.contains("purchase")
                || low.contains("swipe")));
        boolean upiCtx = low.contains("upi");
        boolean strongDebit = low.contains("debit") || low.contains("spent") || low.contains("purchase")
            || low.contains("paid") || low.contains("withdrawn") || low.contains("charged")
            || low.contains("txn of") || low.contains("redeemed")
            || low.contains("payment successful");
        boolean strongCredit = low.contains("credit") || low.contains("received")
            || low.contains("deposited") || low.contains("deposit") || low.contains("refund");
        // Note: "cashback" alone is NOT a credit signal ("Get 10% cashback on
        // Rs 5000 spends" is a promo). Real cashback credits always pair it
        // with credited/received/deposited.
        boolean drAbbr = DR_ABBR.matcher(low).find();
        boolean crAbbr = CR_ABBR.matcher(low).find();
        boolean debitW = strongDebit || drAbbr;
        boolean creditW = strongCredit || crAbbr;
        boolean transferW = low.contains("own account") || low.contains("self")
            || low.contains("fund transfer") || low.contains("transferred to");

        // Direction anchored on the user's own account beats generic verb order
        // ("Your a/c ... is debited ... and credited to a/c ..." vs the reverse).
        String ownDir = ownAccountDirection(body);
        if (ownDir != null) {
            if (cardCtx && "DEBIT".equals(ownDir)
                    && (low.contains("spent") || low.contains("purchase"))) {
                return "CARD_SPEND";
            }
            return ownDir;
        }

        if (cardCtx && debitW) return "CARD_SPEND";
        if (transferW && (debitW || low.contains("transfer"))) return "TRANSFER";
        String t = low.trim();
        // "Sent Rs...", "Money Sent: Rs...", "Txn Rs..." debit shapes.
        if ((t.startsWith("sent ") || t.startsWith("txn ") || low.contains("money sent"))
                && !creditW) {
            return "DEBIT";
        }
        // E-mandate debits with no explicit debit verb ("...processed successfully").
        if (low.contains("mandate") && low.contains("process") && !creditW) return "DEBIT";
        if (debitW && !creditW) return "DEBIT";
        if (creditW && !debitW) return "CREDIT";
        // "credited to Dr. Sharma": the only debit signal is the Dr. abbreviation.
        if (drAbbr && !strongDebit && strongCredit) return "CREDIT";
        // UPI "transaction of Rs X ... is successful" (money sent, no explicit debit verb)
        if (upiCtx && (low.contains("transaction of") || low.contains("payment of"))
                && (low.contains("successful") || low.contains("success"))
                && !creditW) return "DEBIT";
        // Gulf style: "Successful transaction of QAR 250.00 @ MERCHANT"
        // (a purchase; credit wordings always name credited/received instead).
        if (low.contains("successful transaction") && !creditW) {
            return cardCtx ? "CARD_SPEND" : "DEBIT";
        }
        if (debitW) return "DEBIT";
        if (creditW) return "CREDIT";
        return null;
    }

    /** CREDIT/DEBIT/NULL based on "Your a/c ... is credited/debited" wording. */
    private static String ownAccountDirection(String body) {
        Matcher mc = OWN_CREDITED.matcher(body);
        Matcher md = OWN_DEBITED.matcher(body);
        boolean c = mc.find();
        boolean d = md.find();
        if (c && d) return mc.start() < md.start() ? "CREDIT" : "DEBIT";
        if (c) return "CREDIT";
        if (d) return "DEBIT";
        return null;
    }

    /** Alerts shaped like transactions where no money moved. */
    private static boolean isNonTxnAlert(String low) {
        if (low.contains("is due on")) return true;                 // Kotak bill reminder
        if (low.contains("mandate") && low.contains("creat")) return true; // PNB mandate created
        if (low.contains("lien of") || low.contains("lien marked")) return true; // IDFC ASBA block
        if (low.contains("e-voucher") || low.contains("evoucher")) return true;
        // Marketing promos ("Get 10% cashback...", "T&C apply") - no money moved.
        if (low.contains("t&c") || low.contains("t and c")
                || low.contains("terms and conditions")) {
            return true;
        }
        // Beneficiary confirmation of an OUTGOING transfer ("X has received Rs N
        // from your A/c ...") - the debit SMS already recorded it.
        if (Pattern.compile("(?i)has received (?:rs\\.?|inr|\\u20B9)?\\s*[0-9,.]+[^.]*?from your a/?c")
                .matcher(low).find()) {
            return true;
        }
        // Failed / declined / insufficient-funds: nothing moved
        // (unless it's a refund/reversal, which IS money back).
        if ((low.contains("insufficient") || low.contains("failed") || low.contains("declined")
                    || low.contains("unsuccessful"))
                && !low.contains("refund") && !low.contains("revers")) {
            return true;
        }
        // Australia-style non-transaction alerts (also match Indian OTP SMS):
        // one-time codes, fraud yes/no checks, paused-payment verifications,
        // card-status notices.
        if (low.contains("otp") || low.contains("one-time") || low.contains("passcode")
                || low.contains("security code") || low.contains("verification code")
                || low.contains("do not reply") || low.contains("reply yes")
                || low.contains("yes/no") || low.contains("paused")
                || low.contains("verify your") || low.contains("was this you")
                || low.contains("card has been mailed") || low.contains("card is on its way")) {
            return true;
        }
        return false;
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
            String s = cleanMerchant(body.substring(start, Math.min(end, start + 64)));
            if (s.isEmpty()) continue;
            // Skip pure numbers / numeric VPAs ("25000@10000") and filler words.
            if (s.matches("(?i)^[0-9@.\\s]+$")) continue;
            String l = s.toLowerCase(Locale.US);
            if (l.equals("pay") || l.equals("payment") || l.equals("payments")
                    || l.equals("transfer") || l.equals("upi")) {
                continue;
            }
            // Skip "credited to your A/c ...", "from HDFC Bank ...",
            // "paid from account XXXXXX4567" etc.
            if (l.startsWith("your ") || l.startsWith("my ") || l.startsWith("our ")
                    || l.startsWith("the ") || l.contains(" bank") || l.contains("a/c")
                    || l.contains("acct")
                    || Pattern.compile("(?i)\\baccount\\s+[x*\\d]*\\d{3,}").matcher(s).find()) {
                continue;
            }
            return s;
        }
        // Fallback: UPI narrations like UPI/P2M/<rrn>/<merchant>.
        Matcher um = Pattern.compile("(?i)\\bupi/[^/\\s]+/\\d+/([^/\\n]{2,44})").matcher(body);
        while (um.find()) {
            String s = cleanMerchant(um.group(1));
            if (s.isEmpty() || s.matches("(?i)^[0-9@.\\s]+$")) continue;
            String l = s.toLowerCase(Locale.US);
            if (l.contains("bank") || l.contains("a/c")) continue;
            return s;
        }
        return "";
    }

    /** Trims a raw merchant candidate at trailer words/punctuation. */
    private static String cleanMerchant(String s) {
        s = s.trim().replaceAll("^[\\s:;,-]+", "").replaceAll("[\\s:;,.-]+$", "");
        String l = s.toLowerCase(Locale.US);
        int best = s.length();
        for (String cut : new String[]{" on ", " via ", " is ", " was ", " has ",
                                       " ref ", " ref:", ".ref", " txn ", " not ", " rrn",
                                       ".rrn", " upi:", " upi ", " using ", " for ",
                                       " bal", " avl", " -", ";", ","}) {
            int i = l.indexOf(cut);
            if (i > 1 && i < best) best = i;
        }
        // Cut at date shapes ("... 16-NOV-2025").
        Matcher dm = Pattern.compile("\\b\\d{1,2}[-/][A-Za-z]{3}[-/]\\d{2,4}\\b").matcher(s);
        if (dm.find() && dm.start() > 1 && dm.start() < best) best = dm.start();
        s = s.substring(0, best).trim().replaceAll("[\\s:;,.-]+$", "");
        // Drop noise prefixes like "VPA " / "UPI/" / "UPI/DR/<rrn>/" and a
        // trailing single-letter UPI tag ("/u") BEFORE the length cap, so the
        // cap never chops a real name that follows a narration prefix.
        s = s.replaceFirst("(?i)^vpa\\s+", "")
             .replaceFirst("(?i)^upi/[^/]+/[A-Z]?\\d+/", "")
             .replaceFirst("(?i)^upi/", "")
             .replaceFirst("/[a-zA-Z]$", "");
        if (s.length() > 34) s = s.substring(0, 34).trim().replaceAll("[\\s:;,.-]+$", "");
        return s.length() >= 2 ? s : "";
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
        while (m.find()) out.add(parseNum(m.group(2)));
        return out;
    }
}
