/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

/**
 * Auto-pay reminders. One alarm per card with auto-pay on, firing at 9 AM
 * two days before the debit (due) date so the user can check the linked
 * account has enough balance. Idempotent: re-scheduling replaces the
 * previous alarm for the same card, and cards with auto-pay off or already
 * paid have their alarm cancelled. Alarms are plain (inexact) so no exact
 * alarm permission is needed; the receiver re-checks the due before
 * notifying, so a late alarm is harmless.
 */
public class AutoPayScheduler {

    public static final String ACTION_AUTOPAY_REMINDER =
        "com.local.expensetracker.AUTOPAY_REMINDER";
    public static final String EXTRA_CARD_KEY = "cardKey";
    private static final long DAY_MS = 86400000L;

    public static void schedule(Context ctx) {
        DbHelper db = new DbHelper(ctx);
        List<CardDue> dues;
        try {
            dues = db.getDues();
        } finally {
            db.close();
        }
        AlarmManager am =
            (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        for (CardDue d : dues) {
            Intent i = new Intent(ctx, AutoPayReceiver.class);
            i.setAction(ACTION_AUTOPAY_REMINDER);
            i.putExtra(EXTRA_CARD_KEY, d.cardKey);
            PendingIntent pi = PendingIntent.getBroadcast(ctx,
                d.cardKey.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            boolean want = d.autoPayMode != null
                && !"OFF".equals(d.autoPayMode)
                && !"PAID".equals(d.status)
                && (d.totalDue - d.paid) > 0.005
                && d.dueTs > now;
            if (!want) {
                am.cancel(pi);
                continue;
            }
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(d.dueTs - 2 * DAY_MS);
            c.set(Calendar.HOUR_OF_DAY, 9);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long when = c.getTimeInMillis();
            if (when <= now) when = now + 60_000L; // due within 2 days: remind shortly
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, when, pi);
            }
        }
    }
}
