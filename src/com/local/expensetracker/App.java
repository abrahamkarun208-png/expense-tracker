/* Copyright (c) 2026 Chris. All rights reserved. */

package com.local.expensetracker;

import android.app.Application;

/** Loads the user's name and their credit cards into the parser at
 *  process start, so both the activity and the background SMS receiver
 *  spot self-transfers and card-account credits.
 *  The name and card digits never leave the phone. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Same file Activity.getPreferences() uses (local class name).
        android.content.SharedPreferences p =
            getSharedPreferences("MainActivity", MODE_PRIVATE);
        SmsParser.setUserNames(p.getString("user_name", ""),
            p.getString("user_name_alias", ""));
        SmsParser.setMyCards(p.getStringSet("my_cards", null));
    }
}
