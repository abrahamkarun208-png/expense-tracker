/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

public class Transaction {
    public String key;
    public String bankCode;
    public String bankName;
    public double amount;
    public String type;      // DEBIT, CREDIT, TRANSFER, CARD_SPEND
    public String merchant;
    public long ts;
    public String card4;
    public String currency;   // INR, QAR, AUD, USD
    /** For TRANSFER rows only: OUT (money left this account) or IN
     *  (money arrived here). Null when the direction was never recorded
     *  (older rows, or a transfer shape with no direction signal). */
    public String transferDir;
    /** Links the two legs of one transfer (same value on both rows). */
    public String pairKey;
}
