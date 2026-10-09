/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import android.app.Application;

/** Loads the user's name into the parser at process start, so both the
 *  activity and the background SMS receiver spot self-transfers.
 *  The name never leaves the phone. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Same file Activity.getPreferences() uses (local class name).
        String name = getSharedPreferences("MainActivity", MODE_PRIVATE)
            .getString("user_name", "");
        SmsParser.setUserName(name);
    }
}
