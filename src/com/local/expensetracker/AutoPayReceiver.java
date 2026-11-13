/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fires an auto-pay reminder alarm, or re-schedules all reminders after a
 * reboot. Before notifying it re-reads the due: if the bill was already
 * paid (or the auto-pay mode was turned off) nothing is shown. The
 * notification only appears when the POST_NOTIFICATIONS permission was
 * granted; without it the in-app due row still shows the auto-pay note.
 */
public class AutoPayReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "autopay";
    private static final long DAY_MS = 86400000L;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            AutoPayScheduler.schedule(ctx);
            return;
        }
        if (!AutoPayScheduler.ACTION_AUTOPAY_REMINDER.equals(action)) return;
        String cardKey = intent.getStringExtra(AutoPayScheduler.EXTRA_CARD_KEY);
        if (cardKey == null) return;

        DbHelper db = new DbHelper(ctx);
        CardDue d = null;
        try {
            for (CardDue x : db.getDues()) {
                if (cardKey.equals(x.cardKey)) { d = x; break; }
            }
        } finally {
            db.close();
        }
        if (d == null || "PAID".equals(d.status)) return;
        double remaining = d.totalDue - d.paid;
        if (remaining <= 0.005) return;
        long now = System.currentTimeMillis();
        if (d.dueTs <= now) return; // already due: the in-app row shows it
        if (Build.VERSION.SDK_INT >= 33
            && ctx.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                "Auto-pay reminders",
                NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(
                "Reminds you before a credit card auto-pay debit");
            nm.createNotificationChannel(ch);
        }
        long days = (d.dueTs - now + DAY_MS - 1) / DAY_MS;
        String when = days <= 1 ? "tomorrow" : "in " + days + " days";
        String mode = "TOTAL".equals(d.autoPayMode) ? "total due" : "minimum due";
        String date = new SimpleDateFormat("d MMM", Locale.getDefault())
            .format(new Date(d.dueTs));
        String title = "Auto-pay " + when + ": " + d.bankName + " card";
        String text = MoneyFmt.money(Math.max(remaining, 0), d.currency)
            + " (" + mode + ") will be debited on " + date
            + ". Check your account balance.";
        Notification.Builder b = new Notification.Builder(ctx)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true);
        if (Build.VERSION.SDK_INT >= 26) b.setChannelId(CHANNEL);
        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setContentIntent(pi);
        nm.notify(cardKey.hashCode(), b.build());
    }
}
