/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;

/** Receives incoming SMS and imports bank messages live. */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;
        Bundle b = intent.getExtras();
        if (b == null) return;
        Object[] pdus = (Object[]) b.get("pdus");
        if (pdus == null || pdus.length == 0) return;
        String format = b.getString("format");

        // Reassemble multipart SMS into one message before importing,
        // so a long SMS is never stored as several partial rows.
        String addr = null;
        StringBuilder body = new StringBuilder();
        long ts = 0;
        for (Object pdu : pdus) {
            SmsMessage m;
            if (Build.VERSION.SDK_INT >= 23) {
                m = SmsMessage.createFromPdu((byte[]) pdu, format);
            } else {
                m = SmsMessage.createFromPdu((byte[]) pdu);
            }
            if (m == null) continue;
            if (addr == null) {
                addr = m.getDisplayOriginatingAddress();
                ts = m.getTimestampMillis();
            }
            body.append(m.getDisplayMessageBody());
        }
        if (addr == null) return;

        final String fAddr = addr;
        final String fBody = body.toString();
        final long fTs = ts;
        final Context appCtx = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                DbHelper db = new DbHelper(appCtx);
                try {
                    Importer.importOne(db, fAddr, fBody, fTs);
                    db.refreshOverdue(System.currentTimeMillis());
                } finally {
                    db.close();
                }
            }
        }).start();
    }
}
