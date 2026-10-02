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

        final String[] addr = new String[pdus.length];
        final String[] body = new String[pdus.length];
        final long[] ts = new long[pdus.length];
        for (int i = 0; i < pdus.length; i++) {
            SmsMessage m;
            if (Build.VERSION.SDK_INT >= 23) {
                m = SmsMessage.createFromPdu((byte[]) pdus[i], format);
            } else {
                m = SmsMessage.createFromPdu((byte[]) pdus[i]);
            }
            if (m == null) continue;
            addr[i] = m.getDisplayOriginatingAddress();
            body[i] = m.getDisplayMessageBody();
            ts[i] = m.getTimestampMillis();
        }

        final Context appCtx = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                DbHelper db = new DbHelper(appCtx);
                try {
                    for (int i = 0; i < addr.length; i++) {
                        if (addr[i] != null && body[i] != null) {
                            Importer.importOne(db, addr[i], body[i], ts[i]);
                        }
                    }
                    db.refreshOverdue(System.currentTimeMillis());
                } finally {
                    db.close();
                }
            }
        }).start();
    }
}
