package com.local.expensetracker;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CalendarView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Expense Tracker. Reads bank SMS on this device only.
 * No network permission is declared: the app cannot send data anywhere.
 */
public class MainActivity extends Activity {

    private static final int REQ_SMS = 1001;
    private static final long DAY_MS = 86400000L;

    private DbHelper db;
    private LinearLayout txnContainer;
    private LinearLayout dueContainer;
    private TextView totalView;
    private TextView totalLabel;
    private TextView txnCountView;
    private TextView permNotice;
    private Button permButton;
    private Button btnDay, btnWeek, btnMonth, btnCal;
    private LinearLayout calCard;
    private CalendarView calView;
    private LinearLayout pieCard;
    private PieChartView pieChart;
    private LinearLayout pieLegend;
    private TextView emptyTxns;
    private TextView emptyDues;

    private int period = 0; // 0=day, 1=week, 2=month, 3=calendar
    private long calDayMs;
    private static long lastImportMs = 0;

    private static final int[] PIE_COLORS = {
        0xFF14532D, 0xFF16A34A, 0xFF0EA5E9, 0xFFF59E0B, 0xFF8B5CF6, 0xFF64748B
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new DbHelper(this);

        txnContainer = findViewById(R.id.txnContainer);
        dueContainer = findViewById(R.id.dueContainer);
        emptyTxns = findViewById(R.id.emptyTxns);
        emptyDues = findViewById(R.id.emptyDues);
        totalView = findViewById(R.id.totalView);
        totalLabel = findViewById(R.id.totalLabel);
        txnCountView = findViewById(R.id.txnCountView);
        permNotice = findViewById(R.id.permNotice);
        permButton = findViewById(R.id.permButton);
        btnDay = findViewById(R.id.btnDay);
        btnWeek = findViewById(R.id.btnWeek);
        btnMonth = findViewById(R.id.btnMonth);
        btnCal = findViewById(R.id.btnCal);
        calCard = findViewById(R.id.calCard);
        calView = findViewById(R.id.calView);
        pieCard = findViewById(R.id.pieCard);
        pieChart = findViewById(R.id.pieChart);
        pieLegend = findViewById(R.id.pieLegend);

        db.removeNearDuplicates();

        calDayMs = TxnGrouper.dayStart(System.currentTimeMillis());
        calView.setMaxDate(System.currentTimeMillis());
        calView.setOnDateChangeListener(new CalendarView.OnDateChangeListener() {
            @Override
            public void onSelectedDayChange(CalendarView view, int y, int m, int d) {
                Calendar c = Calendar.getInstance();
                c.set(y, m, d, 0, 0, 0);
                c.set(Calendar.MILLISECOND, 0);
                calDayMs = c.getTimeInMillis();
                refreshUi();
            }
        });

        View.OnClickListener periodClick = new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (v == btnDay) period = 0;
                else if (v == btnWeek) period = 1;
                else if (v == btnMonth) period = 2;
                else period = 3;
                stylePeriodButtons();
                refreshUi();
            }
        };
        btnDay.setOnClickListener(periodClick);
        btnWeek.setOnClickListener(periodClick);
        btnMonth.setOnClickListener(periodClick);
        btnCal.setOnClickListener(periodClick);
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
        Button[] btns = {btnDay, btnWeek, btnMonth, btnCal};
        int selBg = R.drawable.tab_selected;
        int selFg = 0xFFFFFFFF;
        int unselFg = getColor(R.color.ink);
        for (int i = 0; i < btns.length; i++) {
            if (i == period) {
                btns[i].setBackgroundResource(selBg);
                btns[i].setTextColor(selFg);
            } else {
                btns[i].setBackgroundResource(0);
                btns[i].setTextColor(unselFg);
            }
        }
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
        long now = System.currentTimeMillis();
        long start, end;
        String label;
        if (period == 0) {
            start = TxnGrouper.dayStart(now);
            end = now;
            label = "Spent today";
        } else if (period == 1) {
            start = TxnGrouper.dayStart(now - 6 * DAY_MS);
            end = now;
            label = "Spent in the last 7 days";
        } else if (period == 2) {
            start = TxnGrouper.dayStart(now - 29 * DAY_MS);
            end = now;
            label = "Spent in the last 30 days";
        } else {
            start = calDayMs;
            end = calDayMs + DAY_MS - 1;
            label = "Spent on " + new SimpleDateFormat("dd MMM",
                Locale.getDefault()).format(new Date(calDayMs));
        }
        calCard.setVisibility(period == 3 ? View.VISIBLE : View.GONE);

        double total = db.sumSpentBetween(start, end);
        totalView.setText("\u20B9" + String.format(Locale.US, "%,.0f", total));
        totalLabel.setText(label);

        List<Transaction> txns = db.getTxnsBetween(start, end);
        int n = txns.size();
        txnCountView.setText(n == 1 ? "1 transaction" : n + " transactions");

        List<Object> rows;
        if (period == 1 || period == 2) {
            rows = TxnGrouper.groupByDay(txns);
        } else {
            rows = new ArrayList<Object>(txns);
        }
        new TxnAdapter(this, rows).populate(txnContainer);
        emptyTxns.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);

        boolean showPie = (period == 1 || period == 2);
        pieCard.setVisibility(showPie ? View.VISIBLE : View.GONE);
        if (showPie) updatePie(txns);

        List<CardDue> dues = db.getDues();
        new DueAdapter(this, dues).populate(dueContainer);
        emptyDues.setVisibility(dues.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Builds the merchant-breakdown donut for weekly/monthly tabs. */
    private void updatePie(List<Transaction> txns) {
        Map<String, Double> byMerchant = new LinkedHashMap<String, Double>();
        for (Transaction t : txns) {
            if (!("DEBIT".equals(t.type) || "CARD_SPEND".equals(t.type))) continue;
            String m = (t.merchant == null || t.merchant.isEmpty()) ? "Others" : t.merchant;
            Double v = byMerchant.get(m);
            byMerchant.put(m, (v == null ? 0 : v) + t.amount);
        }
        List<Map.Entry<String, Double>> entries =
            new ArrayList<Map.Entry<String, Double>>(byMerchant.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Double>>() {
            @Override public int compare(Map.Entry<String, Double> a,
                                         Map.Entry<String, Double> b) {
                return Double.compare(b.getValue(), a.getValue());
            }
        });

        double total = 0;
        for (Map.Entry<String, Double> e : entries) total += e.getValue();

        List<PieChartView.Slice> slices = new ArrayList<PieChartView.Slice>();
        double others = 0;
        int n = Math.min(5, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            if (i < n) {
                slices.add(new PieChartView.Slice(entries.get(i).getKey(),
                    entries.get(i).getValue(), PIE_COLORS[i % PIE_COLORS.length]));
            } else {
                others += entries.get(i).getValue();
            }
        }
        if (others > 0) {
            slices.add(new PieChartView.Slice("Others", others,
                PIE_COLORS[PIE_COLORS.length - 1]));
        }
        pieChart.setData(slices, total, PieChartView.money(total));

        pieLegend.removeAllViews();
        if (slices.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No spending in this period.");
            t.setTextSize(13);
            t.setTextColor(getColor(R.color.muted));
            pieLegend.addView(t);
            return;
        }
        for (PieChartView.Slice s : slices) addLegendRow(s, total);
    }

    private void addLegendRow(PieChartView.Slice s, double total) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vpad = dp(3);
        row.setPadding(0, vpad, 0, vpad);

        TextView dot = new TextView(this);
        dot.setText("\u25CF");
        dot.setTextColor(s.color);
        dot.setTextSize(13);

        TextView name = new TextView(this);
        String label = s.label.length() > 16 ? s.label.substring(0, 15) + "\u2026" : s.label;
        name.setText(label);
        name.setTextSize(13);
        name.setTextColor(getColor(R.color.ink));
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        int hpad = dp(8);
        lp.setMargins(hpad, 0, hpad, 0);
        name.setLayoutParams(lp);

        TextView amt = new TextView(this);
        int pct = total > 0 ? (int) Math.round(s.value / total * 100) : 0;
        amt.setText(PieChartView.money(s.value) + " \u00B7 " + pct + "%");
        amt.setTextSize(12);
        amt.setTextColor(getColor(R.color.muted));

        row.addView(dot);
        row.addView(name);
        row.addView(amt);
        pieLegend.addView(row);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
