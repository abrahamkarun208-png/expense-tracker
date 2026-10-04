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
}
