/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private static final int REQ_RECEIVE_SMS = 1002;
    private static final long DAY_MS = 86400000L;

    private DbHelper db;
    private LinearLayout txnContainer;
    private LinearLayout dueContainer;
    private TextView permNotice;
    private Button permButton;
    // NET hero card
    private TextView netLabel;
    private TextView netAmount;
    private TextView incomeTileAmt;
    private TextView expenseTileAmt;
    private View netBarFill;
    private View netBarRest;
    private TextView pctCaption;
    private TextView duesLine;
    // Month navigator (drives NET card, Month tab and Calendar)
    private TextView monthTitle;
    private int navYear;
    private int navMonth;
    // Period tabs
    private LinearLayout tabDay, tabWeek, tabMonth, tabCal;
    private TextView tabDayText, tabWeekText, tabMonthText, tabCalText;
    private View tabDayInd, tabWeekInd, tabMonthInd, tabCalInd;
    private LinearLayout calCard;
    private SpendCalendarView spendCal;
    private UpdateChecker updateChecker;
    private LinearLayout pieCard;
    private PieChartView pieChart;
    private LinearLayout pieLegend;
    private List<PieChartView.Slice> pieSlices = new ArrayList<PieChartView.Slice>();
    private List<List<Transaction>> pieMembers =
        new ArrayList<List<Transaction>>();
    private TextView emptyTxns;
    private TextView emptyDues;

    private int period = 0; // 0=day, 1=week, 2=month, 3=calendar
    private long calDayMs;
    private static long lastImportMs = 0;
    private boolean receiverRegistered = false;

    /** Refreshes the UI when the SMS receiver imports a message live. */
    private final BroadcastReceiver smsImportedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            refreshUi();
        }
    };

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
        netLabel = findViewById(R.id.netLabel);
        netAmount = findViewById(R.id.netAmount);
        incomeTileAmt = findViewById(R.id.incomeTileAmt);
        expenseTileAmt = findViewById(R.id.expenseTileAmt);
        netBarFill = findViewById(R.id.netBarFill);
        netBarRest = findViewById(R.id.netBarRest);
        pctCaption = findViewById(R.id.pctCaption);
        duesLine = findViewById(R.id.duesLine);
        monthTitle = findViewById(R.id.monthTitle);
        tabDay = findViewById(R.id.tabDay);
        tabWeek = findViewById(R.id.tabWeek);
        tabMonth = findViewById(R.id.tabMonth);
        tabCal = findViewById(R.id.tabCal);
        tabDayText = findViewById(R.id.tabDayText);
        tabWeekText = findViewById(R.id.tabWeekText);
        tabMonthText = findViewById(R.id.tabMonthText);
        tabCalText = findViewById(R.id.tabCalText);
        tabDayInd = findViewById(R.id.tabDayInd);
        tabWeekInd = findViewById(R.id.tabWeekInd);
        tabMonthInd = findViewById(R.id.tabMonthInd);
        tabCalInd = findViewById(R.id.tabCalInd);
        permNotice = findViewById(R.id.permNotice);
        permButton = findViewById(R.id.permButton);
        calCard = findViewById(R.id.calCard);
        spendCal = findViewById(R.id.spendCal);
        spendCal.setNavVisible(false); // the top month navigator drives it
        spendCal.setOnDaySelectListener(new SpendCalendarView.OnDaySelectListener() {
            @Override public void onDaySelect(long dayStartMs) {
                calDayMs = dayStartMs;
                refreshUi();
                if (spendCal.hasActivity(dayStartMs)) showDayMerchants(dayStartMs);
            }
        });
        pieCard = findViewById(R.id.pieCard);
        pieChart = findViewById(R.id.pieChart);
        pieLegend = findViewById(R.id.pieLegend);
        pieChart.setOnSliceClickListener(new PieChartView.OnSliceClickListener() {
            @Override public void onSliceClick(int index) {
                if (index >= 0 && index < pieSlices.size()
                        && index < pieMembers.size()) {
                    showMerchantDetail(pieSlices.get(index), pieMembers.get(index),
                        period == 1 ? "this week" : monthTitle.getText().toString());
                }
            }
        });

        db.removeNearDuplicates();

        // Footer shows the version so a bug report can name it.
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.appVersion)).setText("\u00A9 2026 Chris \u00B7 v" + vn);
        } catch (Exception ignored) {}

        calDayMs = TxnGrouper.dayStart(System.currentTimeMillis());
        Calendar navInit = Calendar.getInstance();
        navYear = navInit.get(Calendar.YEAR);
        navMonth = navInit.get(Calendar.MONTH);

        updateChecker = new UpdateChecker(this);
        TextView checkUpdates = findViewById(R.id.checkUpdates);
        checkUpdates.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                updateChecker.checkNow(true);
            }
        });
        updateChecker.checkIfDue();

        View.OnClickListener periodClick = new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (v == tabDay) period = 0;
                else if (v == tabWeek) period = 1;
                else if (v == tabMonth) period = 2;
                else {
                    period = 3;
                    syncCalendar();
                }
                stylePeriodTabs();
                refreshUi();
            }
        };
        tabDay.setOnClickListener(periodClick);
        tabWeek.setOnClickListener(periodClick);
        tabMonth.setOnClickListener(periodClick);
        tabCal.setOnClickListener(periodClick);
        stylePeriodTabs();

        findViewById(R.id.monthPrev).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shiftMonth(-1); }
        });
        findViewById(R.id.monthNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shiftMonth(1); }
        });

        // Tapping either tile shows every account's income and expenses.
        View.OnClickListener tileClick = new View.OnClickListener() {
            @Override public void onClick(View v) { showAccountBreakdown(); }
        };
        findViewById(R.id.tileIncome).setOnClickListener(tileClick);
        findViewById(R.id.tileExpense).setOnClickListener(tileClick);

        permButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askPermission(); }
        });

        findViewById(R.id.rescan).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (hasSms()) {
                    // Manual rescan always re-reads the whole inbox, so any
                    // message missed earlier (new bank, parser update, or an
                    // interrupted scan) gets another chance.
                    importSms(true);
                } else {
                    askPermission();
                }
            }
        });

        if (hasSms()) {
            hidePermUi();
            ensureReceivePermission();
            importSms(false);
        } else {
            showPermUi();
            refreshUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            // Android 13+ requires an explicit exported/not-exported flag;
            // the flag-less call throws SecurityException and kills the app.
            // Our broadcast is package-scoped/internal: not exported.
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(smsImportedReceiver,
                    new IntentFilter(SmsReceiver.ACTION_SMS_IMPORTED),
                    Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(smsImportedReceiver,
                    new IntentFilter(SmsReceiver.ACTION_SMS_IMPORTED));
            }
            receiverRegistered = true;
        }
        if (hasSms()) {
            // Incremental scan: only messages since the last scan are read,
            // so this is cheap enough to run on every return to the app.
            // New bank SMS are picked up without any manual rescan.
            importSms(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            unregisterReceiver(smsImportedReceiver);
            receiverRegistered = false;
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

    /**
     * RECEIVE_SMS was added in v1.10 for live SMS import. Users who granted
     * the SMS permission on an older version were never asked for it, so the
     * SMS receiver silently gets nothing. Ask once; if denied, the
     * incremental inbox scan on every resume still keeps data fresh.
     */
    private void ensureReceivePermission() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        if (p.getBoolean("asked_receive_sms", false)) return;
        p.edit().putBoolean("asked_receive_sms", true).apply();
        requestPermissions(
            new String[]{Manifest.permission.RECEIVE_SMS}, REQ_RECEIVE_SMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_RECEIVE_SMS) {
            // Live SMS import permission: granted or not, the incremental
            // scan keeps working. Nothing more to do.
            return;
        }
        if (code == REQ_SMS && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            hidePermUi();
            importSms(true);
        } else {
            showPermUi();
            Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (updateChecker != null) updateChecker.onActivityResult(requestCode);
    }

    private void showPermUi() {
        permNotice.setVisibility(View.VISIBLE);
        permButton.setVisibility(View.VISIBLE);
    }

    private void hidePermUi() {
        permNotice.setVisibility(View.GONE);
        permButton.setVisibility(View.GONE);
    }

    /** Underline-tab styling: selected tab gets ink text + green underline. */
    private void stylePeriodTabs() {
        LinearLayout[] tabs = {tabDay, tabWeek, tabMonth, tabCal};
        TextView[] texts = {tabDayText, tabWeekText, tabMonthText, tabCalText};
        View[] inds = {tabDayInd, tabWeekInd, tabMonthInd, tabCalInd};
        for (int i = 0; i < tabs.length; i++) {
            boolean sel = (i == period);
            texts[i].setTextColor(getColor(sel ? R.color.ink : R.color.muted));
            texts[i].setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            inds[i].setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** Start/end millis of the navigated month. */
    private long[] navMonthRange() {
        Calendar mc = Calendar.getInstance();
        mc.set(navYear, navMonth, 1, 0, 0, 0);
        mc.set(Calendar.MILLISECOND, 0);
        long s = mc.getTimeInMillis();
        mc.add(Calendar.MONTH, 1);
        return new long[]{s, mc.getTimeInMillis() - 1};
    }

    /** Month navigator: never past the current month. */
    private void shiftMonth(int delta) {
        Calendar c = Calendar.getInstance();
        c.set(navYear, navMonth, 1);
        c.add(Calendar.MONTH, delta);
        Calendar now = Calendar.getInstance();
        if (c.get(Calendar.YEAR) > now.get(Calendar.YEAR)
                || (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && c.get(Calendar.MONTH) > now.get(Calendar.MONTH))) {
            return;
        }
        navYear = c.get(Calendar.YEAR);
        navMonth = c.get(Calendar.MONTH);
        if (period == 3) syncCalendar();
        refreshNetCard();
        refreshUi();
    }

    /** Keeps the calendar's selected day inside the navigated month. */
    private void syncCalendar() {
        long[] r = navMonthRange();
        if (calDayMs < r[0] || calDayMs > r[1]) {
            Calendar now = Calendar.getInstance();
            if (navYear == now.get(Calendar.YEAR)
                    && navMonth == now.get(Calendar.MONTH)) {
                calDayMs = TxnGrouper.dayStart(System.currentTimeMillis());
            } else {
                calDayMs = r[0];
            }
        }
        spendCal.showMonth(navYear, navMonth, calDayMs);
        spendCal.setMaps(db.getDailySpending(r[0], r[1]),
            db.getDailyIncome(r[0], r[1]));
    }

    /**
     * The NET hero card: this navigated month's Income, Expenses, Net and
     * expenses-as-%-of-income, matching the approved mockup.
     */
    private void refreshNetCard() {
        long[] r = navMonthRange();
        double exp = db.sumSpentBetween(r[0], r[1]);
        double inc = db.sumIncomeBetween(r[0], r[1]);
        double net = inc - exp;

        String mName = new SimpleDateFormat("MMMM yyyy",
            Locale.getDefault()).format(new Date(r[0]));
        monthTitle.setText(mName);
        Calendar now = Calendar.getInstance();
        boolean cur = navYear == now.get(Calendar.YEAR)
            && navMonth == now.get(Calendar.MONTH);
        netLabel.setText(cur ? "NET THIS MONTH" : "NET \u00B7 "
            + new SimpleDateFormat("MMM yyyy", Locale.getDefault())
                .format(new Date(r[0])).toUpperCase(Locale.getDefault()));

        netAmount.setText((net < 0 ? "-\u20B9" : "+\u20B9")
            + String.format(Locale.US, "%,.0f", Math.abs(net)));
        netAmount.setTextColor(getColor(net < 0 ? R.color.debit : R.color.credit));
        incomeTileAmt.setText("\u20B9" + String.format(Locale.US, "%,.0f", inc));
        expenseTileAmt.setText("\u20B9" + String.format(Locale.US, "%,.0f", exp));

        if (inc > 0) {
            int pct = (int) Math.round(exp / inc * 100);
            int fill = Math.min(pct, 100);
            netBarFill.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, fill));
            netBarRest.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(100 - fill, 1)));
            if (exp <= inc) {
                pctCaption.setText("Expenses used " + pct + "% of income"
                    + " \u2014 you kept " + (100 - pct) + "%");
            } else {
                pctCaption.setText("Expenses were " + pct + "% of income"
                    + " \u2014 you overspent by \u20B9"
                    + String.format(Locale.US, "%,.0f", exp - inc));
            }
        } else if (exp > 0) {
            netBarFill.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 100));
            netBarRest.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
            pctCaption.setText("No income recorded this month");
        } else {
            netBarFill.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
            netBarRest.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 100));
            pctCaption.setText("No activity this month");
        }

        double unpaid = db.unpaidDuesTotal();
        if (unpaid > 0) {
            CardDue first = db.earliestUnpaidDue();
            String dueDate = "";
            if (first != null && first.dueTs > 0) {
                dueDate = ", due " + new SimpleDateFormat("d MMM",
                    Locale.getDefault()).format(new Date(first.dueTs));
            }
            duesLine.setVisibility(View.VISIBLE);
            duesLine.setText("\u20B9"
                + String.format(Locale.US, "%,.0f", unpaid)
                + " of your spending is unpaid card dues" + dueDate + "."
                + " Paying the bill adds \u20B90 to expenses.");
        } else {
            duesLine.setVisibility(View.GONE);
        }
    }

    /**
     * Imports SMS from the inbox on a worker thread.
     *
     * A full scan runs once per app version and whenever the user taps
     * RESCAN (a parser update can make previously-skipped messages
     * parseable, and a manual rescan must never miss old messages).
     * Otherwise only messages newer than the last scan are read, with a 60s
     * overlap, so returning to the app is cheap. Re-reads are harmless:
     * already-imported messages are skipped by their dedup key.
     */
    private void importSms(boolean forceFull) {
        lastImportMs = System.currentTimeMillis();
        final SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String curVer;
        try {
            curVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            curVer = "";
        }
        final boolean full = forceFull
            || !curVer.equals(prefs.getString("last_full_scan_vn", ""));
        final long since = full ? 0
            : Math.max(0, prefs.getLong("last_scan_date", 0) - 60_000);
        final String fCurVer = curVer;
        if (full) Toast.makeText(this, "Reading SMS\u2026", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                int count = 0;
                long maxDate = since;
                Cursor c = null;
                try {
                    String sel = since > 0 ? "date > ?" : null;
                    String[] args = since > 0
                        ? new String[]{String.valueOf(since)} : null;
                    c = getContentResolver().query(
                        Uri.parse("content://sms/inbox"),
                        new String[]{"_id", "address", "body", "date"},
                        sel, args, "date DESC");
                    if (c != null) {
                        while (c.moveToNext()) {
                            String addr = c.getString(1);
                            String body = c.getString(2);
                            long ts = c.getLong(3);
                            if (addr != null && body != null) {
                                try {
                                    Importer.importOne(db, addr, body, ts);
                                } catch (Exception e) {
                                    // One bad message must never kill the scan.
                                }
                                count++;
                            }
                            if (ts > maxDate) maxDate = ts;
                        }
                    }
                    db.refreshOverdue(System.currentTimeMillis());
                } catch (SecurityException e) {
                    // permission revoked mid-import
                } finally {
                    if (c != null) c.close();
                }
                SharedPreferences.Editor ed = prefs.edit();
                if (maxDate > 0) ed.putLong("last_scan_date", maxDate);
                if (full) ed.putString("last_full_scan_vn", fCurVer);
                ed.apply();
                final int done = count;
                final boolean fFull = full;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshUi();
                        if (fFull || done > 0) {
                            Toast.makeText(MainActivity.this,
                                fFull ? "Scanned " + done + " messages"
                                      : "Checked " + done + " new messages",
                                Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void refreshUi() {
        refreshNetCard();
        long now = System.currentTimeMillis();
        long start, end;
        if (period == 0) {
            start = TxnGrouper.dayStart(now);
            end = now;
        } else if (period == 1) {
            // Calendar week: Sunday to Saturday.
            start = TxnGrouper.weekStart(now);
            end = now;
        } else if (period == 2) {
            // The navigated calendar month, from the 1st.
            long[] r = navMonthRange();
            start = r[0];
            end = r[1];
        } else {
            syncCalendar();
            start = calDayMs;
            end = calDayMs + DAY_MS - 1;
        }
        calCard.setVisibility(period == 3 ? View.VISIBLE : View.GONE);

        List<Transaction> txns = db.getTxnsBetween(start, end);
        // Day-grouped headers everywhere: "TODAY \u00B7 SAT 3 OCT" with
        // Exp/Inc totals, matching the approved mockup.
        List<Object> rows = TxnGrouper.groupByDay(txns);
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
        Map<String, List<Transaction>> byMerchant = new LinkedHashMap<String, List<Transaction>>();
        for (Transaction t : txns) {
            if (!("DEBIT".equals(t.type) || "CARD_SPEND".equals(t.type))) continue;
            String m = (t.merchant == null || t.merchant.isEmpty()) ? "Unknown" : t.merchant;
            List<Transaction> l = byMerchant.get(m);
            if (l == null) {
                l = new ArrayList<Transaction>();
                byMerchant.put(m, l);
            }
            l.add(t);
        }
        List<Map.Entry<String, List<Transaction>>> entries =
            new ArrayList<Map.Entry<String, List<Transaction>>>(byMerchant.entrySet());
        Collections.sort(entries,
            new Comparator<Map.Entry<String, List<Transaction>>>() {
                @Override public int compare(Map.Entry<String, List<Transaction>> a,
                                             Map.Entry<String, List<Transaction>> b) {
                    return Double.compare(sumOf(b.getValue()), sumOf(a.getValue()));
                }
            });

        double total = 0;
        for (Map.Entry<String, List<Transaction>> e : entries) total += sumOf(e.getValue());

        pieSlices.clear();
        pieMembers.clear();
        int n = Math.min(5, entries.size());
        List<Transaction> othersMembers = new ArrayList<Transaction>();
        double others = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (i < n) {
                pieSlices.add(new PieChartView.Slice(entries.get(i).getKey(),
                    sumOf(entries.get(i).getValue()), PIE_COLORS[i % PIE_COLORS.length]));
                pieMembers.add(entries.get(i).getValue());
            } else {
                others += sumOf(entries.get(i).getValue());
                othersMembers.addAll(entries.get(i).getValue());
            }
        }
        if (others > 0) {
            pieSlices.add(new PieChartView.Slice("Others", others,
                PIE_COLORS[PIE_COLORS.length - 1]));
            pieMembers.add(othersMembers);
        }
        pieChart.setData(pieSlices, total, PieChartView.money(total));

        pieLegend.removeAllViews();
        if (pieSlices.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No spending in this period.");
            t.setTextSize(13);
            t.setTextColor(getColor(R.color.muted));
            pieLegend.addView(t);
            return;
        }
        for (int i = 0; i < pieSlices.size(); i++) addLegendRow(i, total);
    }

    private static double sumOf(List<Transaction> txns) {
        double s = 0;
        for (Transaction t : txns) s += t.amount;
        return s;
    }

    private void addLegendRow(final int index, double total) {
        PieChartView.Slice s = pieSlices.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vpad = dp(5);
        row.setPadding(0, vpad, 0, vpad);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showMerchantDetail(pieSlices.get(index), pieMembers.get(index),
                        period == 1 ? "this week" : monthTitle.getText().toString());
            }
        });

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

    /** Tapping a NET-card tile: every account's income and expenses. */
    private void showAccountBreakdown() {
        long[] r = navMonthRange();
        List<DbHelper.AccountSummary> accounts = db.accountSummaries(r[0], r[1]);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Accounts");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.ink));

        TextView sub = new TextView(this);
        sub.setText(monthTitle.getText().toString());
        sub.setTextSize(13);
        sub.setTextColor(getColor(R.color.muted));
        sub.setPadding(0, dp(4), 0, dp(12));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (accounts.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No account activity this month.");
            t.setTextSize(13);
            t.setTextColor(getColor(R.color.muted));
            list.addView(t);
        }
        for (DbHelper.AccountSummary a : accounts) {
            String name = (a.bankName == null || a.bankName.isEmpty())
                ? (a.bankCode == null ? "Unknown" : a.bankCode) : a.bankName;
            if (a.card4 != null && !a.card4.isEmpty()) {
                name += " \u00B7\u00B7\u00B7\u00B7 " + a.card4;
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView nameView = new TextView(this);
            nameView.setText(name);
            nameView.setTextSize(15);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            nameView.setTextColor(getColor(R.color.ink));

            LinearLayout figures = new LinearLayout(this);
            figures.setOrientation(LinearLayout.HORIZONTAL);
            figures.setPadding(0, dp(4), 0, 0);

            TextView inc = new TextView(this);
            inc.setText("\u2191 \u20B9"
                + String.format(Locale.US, "%,.0f", a.income));
            inc.setTextSize(13);
            inc.setTextColor(getColor(R.color.credit));

            TextView exp = new TextView(this);
            exp.setText("\u2193 \u20B9"
                + String.format(Locale.US, "%,.0f", a.expenses));
            exp.setTextSize(13);
            exp.setTextColor(getColor(R.color.debit));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(16), 0, dp(16), 0);
            exp.setLayoutParams(lp);

            TextView net = new TextView(this);
            double n = a.income - a.expenses;
            net.setText("Net " + (n < 0 ? "-\u20B9" : "+\u20B9")
                + String.format(Locale.US, "%,.0f", Math.abs(n)));
            net.setTextSize(13);
            net.setTextColor(getColor(R.color.muted));

            figures.addView(inc);
            figures.addView(exp);
            figures.addView(net);
            row.addView(nameView);
            row.addView(figures);
            list.addView(row);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(list, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(380)));

        root.addView(title);
        root.addView(sub);
        root.addView(sv);

        new AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton("Close", null)
            .show();
    }

    /** Calendar day tap: where the money went that day, by merchant. */
    private void showDayMerchants(final long dayStart) {
        List<Transaction> txns = db.getTxnsBetween(dayStart, dayStart + DAY_MS - 1);
        Map<String, List<Transaction>> byMerchant = new LinkedHashMap<String, List<Transaction>>();
        for (Transaction t : txns) {
            if (!"DEBIT".equals(t.type) && !"CARD_SPEND".equals(t.type)) continue;
            String m = t.merchant == null || t.merchant.isEmpty() ? "Unknown" : t.merchant;
            if (!byMerchant.containsKey(m)) {
                byMerchant.put(m, new ArrayList<Transaction>());
            }
            byMerchant.get(m).add(t);
        }
        final List<Map.Entry<String, List<Transaction>>> entries =
            new ArrayList<Map.Entry<String, List<Transaction>>>(byMerchant.entrySet());
        Collections.sort(entries,
            new Comparator<Map.Entry<String, List<Transaction>>>() {
                @Override public int compare(Map.Entry<String, List<Transaction>> a,
                                             Map.Entry<String, List<Transaction>> b) {
                    return Double.compare(sumOf(b.getValue()), sumOf(a.getValue()));
                }
            });
        double total = 0;
        for (Map.Entry<String, List<Transaction>> e : entries) total += sumOf(e.getValue());
        if (entries.isEmpty()) return;
        final double dayTotal = total;

        final String dateStr = new SimpleDateFormat("dd MMM yyyy",
            Locale.getDefault()).format(new Date(dayStart));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Where you spent");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.ink));

        int n = 0;
        for (Map.Entry<String, List<Transaction>> e : entries) n += e.getValue().size();
        TextView sub = new TextView(this);
        sub.setText(dateStr + " \u00B7 " + PieChartView.money(dayTotal)
            + " \u00B7 " + n + (n == 1 ? " transaction" : " transactions"));
        sub.setTextSize(13);
        sub.setTextColor(getColor(R.color.muted));
        sub.setPadding(0, dp(4), 0, dp(12));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < entries.size(); i++) {
            final Map.Entry<String, List<Transaction>> e = entries.get(i);
            final double mTotal = sumOf(e.getValue());
            final int color = PIE_COLORS[i % PIE_COLORS.length];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int vpad = dp(7);
            row.setPadding(0, vpad, 0, vpad);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showMerchantDetail(
                        new PieChartView.Slice(e.getKey(), mTotal, color),
                        e.getValue(), dateStr);
                }
            });

            TextView dot = new TextView(this);
            dot.setText("\u25CF");
            dot.setTextColor(color);
            dot.setTextSize(13);

            TextView name = new TextView(this);
            String label = e.getKey().length() > 18
                ? e.getKey().substring(0, 17) + "\u2026" : e.getKey();
            name.setText(label);
            name.setTextSize(14);
            name.setTextColor(getColor(R.color.ink));
            LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            int hpad = dp(8);
            lp.setMargins(hpad, 0, hpad, 0);
            name.setLayoutParams(lp);

            TextView amt = new TextView(this);
            int pct = dayTotal > 0 ? (int) Math.round(mTotal / dayTotal * 100) : 0;
            amt.setText(PieChartView.money(mTotal) + " \u00B7 " + pct + "%");
            amt.setTextSize(12);
            amt.setTextColor(getColor(R.color.muted));

            row.addView(dot);
            row.addView(name);
            row.addView(amt);
            list.addView(row);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(list, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(340)));

        root.addView(title);
        root.addView(sub);
        root.addView(sv);

        new AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton("Close", null)
            .show();
    }

    /** Drill-down: this merchant's transactions, grouped by day. */
    private void showMerchantDetail(PieChartView.Slice s, List<Transaction> members,
                                    String periodName) {
        double total = sumOf(members);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(s.label);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.ink));

        TextView sub = new TextView(this);
        String cnt = members.size() == 1 ? "1 transaction" : members.size() + " transactions";
        sub.setText(PieChartView.money(total) + " \u00B7 " + cnt + " \u00B7 " + periodName);
        sub.setTextSize(13);
        sub.setTextColor(getColor(R.color.muted));
        sub.setPadding(0, dp(4), 0, dp(12));

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new TxnAdapter(this, TxnGrouper.groupByDay(members)).populate(list);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(340)));

        root.addView(title);
        root.addView(sub);
        root.addView(sv);

        new AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton("Close", null)
            .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
