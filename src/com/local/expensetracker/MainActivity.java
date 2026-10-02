package com.local.expensetracker;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Expense Tracker. Reads bank SMS on this device only.
 * No network permission is declared: the app cannot send data anywhere.
 */
public class MainActivity extends Activity {

    private static final int REQ_SMS = 1001;

    private DbHelper db;
    private ListView txnList;
    private ListView dueList;
    private TextView totalView;
    private TextView totalLabel;
    private TextView permNotice;
    private Button permButton;
    private Button btnDay, btnWeek, btnMonth;
    private TextView emptyTxns;

    private int period = 0; // 0=day, 1=week, 2=month
    private static long lastImportMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new DbHelper(this);

        txnList = findViewById(R.id.txnList);
        dueList = findViewById(R.id.dueList);
        totalView = findViewById(R.id.totalView);
        totalLabel = findViewById(R.id.totalLabel);
        permNotice = findViewById(R.id.permNotice);
        permButton = findViewById(R.id.permButton);
        btnDay = findViewById(R.id.btnDay);
        btnWeek = findViewById(R.id.btnWeek);
        btnMonth = findViewById(R.id.btnMonth);

        emptyTxns = new TextView(this);
        emptyTxns.setText(R.string.no_txns);
        emptyTxns.setPadding(16, 32, 16, 32);
        txnList.setEmptyView(emptyTxns);
        ((android.view.ViewGroup) txnList.getParent()).addView(emptyTxns);

        View.OnClickListener periodClick = new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (v == btnDay) period = 0;
                else if (v == btnWeek) period = 1;
                else period = 2;
                stylePeriodButtons();
                refreshUi();
            }
        };
        btnDay.setOnClickListener(periodClick);
        btnWeek.setOnClickListener(periodClick);
        btnMonth.setOnClickListener(periodClick);
        stylePeriodButtons();

        permButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askPermission(); }
        });

        findViewById(R.id.rescan).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (hasSms()) {
                    importSms();
                } else {
                    askPermission();
                }
            }
        });

        if (hasSms()) {
            hidePermUi();
            importSms();
        } else {
            showPermUi();
            refreshUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasSms()) {
            // Re-scan when coming back (e.g. permission was granted in Settings),
            // throttled so it doesn't run on every quick switch.
            if (System.currentTimeMillis() - lastImportMs > 30_000) {
                importSms();
            } else {
                refreshUi();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (db != null) db.close();
        super.onDestroy();
    }

    private boolean hasSms() {
        return Build.VERSION.SDK_INT < 23
            || checkSelfPermission(Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)) {
                Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show();
            }
            requestPermissions(
                new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS},
                REQ_SMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_SMS && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            hidePermUi();
            importSms();
        } else {
            showPermUi();
            Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show();
        }
    }

    private void showPermUi() {
        permNotice.setVisibility(View.VISIBLE);
        permButton.setVisibility(View.VISIBLE);
    }

    private void hidePermUi() {
        permNotice.setVisibility(View.GONE);
        permButton.setVisibility(View.GONE);
    }

    private void stylePeriodButtons() {
        btnDay.setAlpha(period == 0 ? 1f : 0.55f);
        btnWeek.setAlpha(period == 1 ? 1f : 0.55f);
        btnMonth.setAlpha(period == 2 ? 1f : 0.55f);
    }

    /** Bulk import from the SMS inbox on a worker thread. */
    private void importSms() {
        lastImportMs = System.currentTimeMillis();
        Toast.makeText(this, "Reading SMS\u2026", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                int count = 0;
                Cursor c = null;
                try {
                    c = getContentResolver().query(
                        Uri.parse("content://sms/inbox"),
                        new String[]{"_id", "address", "body", "date"},
                        null, null, "date DESC");
                    if (c != null) {
                        while (c.moveToNext()) {
                            String addr = c.getString(1);
                            String body = c.getString(2);
                            long ts = c.getLong(3);
                            if (addr != null && body != null) {
                                Importer.importOne(db, addr, body, ts);
                                count++;
                            }
                        }
                    }
                    db.refreshOverdue(System.currentTimeMillis());
                } catch (SecurityException e) {
                    // permission revoked mid-import
                } finally {
                    if (c != null) c.close();
                }
                final int done = count;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshUi();
                        Toast.makeText(MainActivity.this,
                            "Scanned " + done + " messages", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void refreshUi() {
        long cutoff;
        String label;
        Calendar cal = Calendar.getInstance();
        if (period == 0) {
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            cutoff = cal.getTimeInMillis();
            label = "Spent today";
        } else if (period == 1) {
            cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
            label = "Spent in the last 7 days";
        } else {
            cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
            label = "Spent in the last 30 days";
        }

        double total = db.sumSpentSince(cutoff);
        totalView.setText("\u20B9" + String.format(Locale.US, "%,.0f", total));
        totalLabel.setText(label);

        List<Transaction> txns = db.getTxnsSince(cutoff);
        txnList.setAdapter(new TxnAdapter(this, txns));

        List<CardDue> dues = db.getDues();
        dueList.setAdapter(new DueAdapter(this, dues));
    }
}
