/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update: at most once every 48 hours (when the app is opened),
 * asks GitHub's public releases API whether a newer release exists.
 * If the user accepts, the APK is downloaded from github.com and handed
 * to the system installer. This is the app's ONLY network use - no
 * expense data or SMS content is ever sent anywhere.
 */
public class UpdateChecker {

    private static final String PREFS = "update_prefs";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_SKIP = "skip_version";
    private static final long CHECK_INTERVAL_MS = 2L * 24 * 60 * 60 * 1000; // 48h
    private static final String API_URL =
        "https://api.github.com/repos/abrahamkarun208-png/expense-tracker/releases/latest";
    private static final String UA = "ExpenseTracker-Android";
    private static final int REQ_INSTALL_PERMISSION = 9001;

    private final Activity activity;
    private File pendingApk;

    public UpdateChecker(Activity activity) {
        this.activity = activity;
    }

    /** Throttled check - call from onCreate. */
    public void checkIfDue() {
        SharedPreferences p = prefs();
        long last = p.getLong(KEY_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        p.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        checkNow(false);
    }

    /** Immediate check (manual "Check for updates" link). */
    public void checkNow(final boolean forced) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ReleaseInfo info = fetchLatest();
                    if (info != null && isNewer(info.tag, currentVersion())
                            && (forced || !info.tag.equals(
                                prefs().getString(KEY_SKIP, "")))) {
                        showUpdateDialog(info);
                    } else if (forced) {
                        toastOnUi(info != null
                            ? "You're on the latest version."
                            : "Couldn't check for updates. Try again later.");
                    }
                } catch (Exception e) {
                    if (forced) toastOnUi("Couldn't check for updates. Try again later.");
                }
            }
        }).start();
    }

    /** Called from the host activity's onActivityResult. */
    public void onActivityResult(int requestCode) {
        if (requestCode == REQ_INSTALL_PERMISSION && pendingApk != null
                && Build.VERSION.SDK_INT >= 26
                && activity.getPackageManager().canRequestPackageInstalls()) {
            fireInstall(pendingApk);
            pendingApk = null;
        }
    }

    private static class ReleaseInfo {
        String tag;
        String apkUrl;
        String notes;
    }

    private ReleaseInfo fetchLatest() throws Exception {
        HttpURLConnection c =
            (HttpURLConnection) new URL(API_URL).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        if (c.getResponseCode() != 200) return null;
        String body = readAll(c.getInputStream());
        c.disconnect();

        JSONObject o = new JSONObject(body);
        String tag = o.optString("tag_name", "");
        if (tag.isEmpty()) return null;
        String apkUrl = null;
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                String u = assets.getJSONObject(i)
                    .optString("browser_download_url", "");
                if (u.endsWith(".apk")) {
                    apkUrl = u;
                    break;
                }
            }
        }
        if (apkUrl == null) return null;
        ReleaseInfo r = new ReleaseInfo();
        r.tag = tag;
        r.apkUrl = apkUrl;
        r.notes = o.optString("body", "");
        return r;
    }

    private String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        in.close();
        return sb.toString();
    }

    private String currentVersion() {
        try {
            return activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private boolean isNewer(String tag, String current) {
        return compareVersions(stripV(tag), stripV(current)) > 0;
    }

    private String stripV(String v) {
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    private int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseNum(pa[i]) : 0;
            int y = i < pb.length ? parseNum(pb[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private int parseNum(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private void showUpdateDialog(final ReleaseInfo info) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (activity.isFinishing()) return;
                String msg = "Version " + info.tag + " is ready (you have "
                    + currentVersion() + ").";
                if (info.notes != null && !info.notes.isEmpty()) {
                    msg += "\n\n" + info.notes;
                }
                new AlertDialog.Builder(activity)
                    .setTitle("Update available")
                    .setMessage(msg)
                    .setPositiveButton("Update",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                downloadAndInstall(info);
                            }
                        })
                    .setNeutralButton("Skip this version",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                prefs().edit()
                                    .putString(KEY_SKIP, info.tag).apply();
                            }
                        })
                    .setNegativeButton("Later", null)
                    .show();
            }
        });
    }

    private void downloadAndInstall(final ReleaseInfo info) {
        final ProgressDialog pd = new ProgressDialog(activity);
        pd.setTitle("Downloading update");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File dir = activity.getExternalFilesDir("updates");
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    File out = new File(dir,
                        "expense-tracker-" + info.tag + ".apk");
                    HttpURLConnection c = (HttpURLConnection)
                        new URL(info.apkUrl).openConnection();
                    c.setRequestProperty("User-Agent", UA);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(20000);
                    int total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n;
                    long done = 0;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100 / total);
                            activity.runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    pd.setProgress(pct);
                                }
                            });
                        }
                    }
                    fos.close();
                    in.close();
                    c.disconnect();
                    dismissOnUi(pd);
                    promptInstall(out);
                } catch (Exception e) {
                    dismissOnUi(pd);
                    toastOnUi("Update download failed.");
                }
            }
        }).start();
    }

    private void promptInstall(final File apk) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (activity.isFinishing()) return;
                if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager()
                        .canRequestPackageInstalls()) {
                    pendingApk = apk;
                    Toast.makeText(activity,
                        "Allow this app to install updates, then come back.",
                        Toast.LENGTH_LONG).show();
                    Intent i = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivityForResult(i, REQ_INSTALL_PERMISSION);
                    return;
                }
                fireInstall(apk);
            }
        });
    }

    private void fireInstall(File apk) {
        Uri uri = Uri.parse(
            "content://com.local.expensetracker.apkprovider/" + apk.getName());
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(i);
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    private void toastOnUi(final String msg) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void dismissOnUi(final ProgressDialog pd) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    pd.dismiss();
                } catch (Exception ignored) {
                }
            }
        });
    }
}
