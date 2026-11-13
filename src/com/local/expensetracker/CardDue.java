/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

public class CardDue {
    public String cardKey;
    public String bankName;
    public String card4;
    public double totalDue;
    public double minDue;
    public long dueTs;
    public String status;    // PENDING, PAID, OUTSTANDING
    public double paid;
    public String currency;  // INR, QAR, AUD, USD
    /** Auto-pay mode for this card: OFF, TOTAL or MINIMUM. Set by the user
     *  by tapping the card's due row; preserved across statement re-imports. */
    public String autoPayMode; // OFF, TOTAL, MINIMUM
    /** How the last payment arrived: MANUAL or AUTO (auto-debit SMS). */
    public String lastPaySrc;
}
