# Expense Tracker (Android)

A private, fully on-device Android expense tracker. It reads bank SMS
directly on the phone, parses transactions, identifies banks, and shows
spending splits — with **no servers, no accounts, and no internet access**.

## Features

- Reads the SMS inbox (`content://sms/inbox`) and bulk-imports bank messages
- Captures incoming bank SMS live via `SmsReceiver`
- Identifies 27 Indian banks from sender IDs and message text (HDFC, SBI,
  ICICI, Axis, Kotak, PNB, Bank of Baroda, Canara, Yes Bank, IDFC, IDBI,
  Union Bank, IndusInd, Federal, IOB, CUB, RBL, AU Small Finance,
  Standard Chartered, Bank of India, Central Bank of India, Indian Bank,
  UCO Bank, South Indian Bank, Karnataka Bank, Bandhan Bank, Equitas)
- Classifies transactions: **Debit / Credit / Transfer** (own-account) /
  **Card Spend** (kept separate)
- Detects credit-card statements ("Total Amt Due / Min Amt Due / due by")
  to create dues; payment receipts mark them **Paid**; past-due unpaid
  cards show as **Outstanding / Missed**
- Daily / Weekly / Monthly spending totals, transaction list with bank
  badges and type pills, credit-card dues section, rescan button
- Storage: on-device SQLite only (`expenses.db`)

## Privacy

The app declares `READ_SMS` and `RECEIVE_SMS` for its core job (reading
bank SMS on-device). Since v1.10 it also declares `INTERNET` +
`REQUEST_INSTALL_PACKAGES`, used **only** by the built-in self-updater:
at most once every 48 hours the app asks GitHub's releases API whether a
newer version exists, and downloads the APK from github.com only when
you approve the update. No expense data or SMS content ever leaves the
device. See [PRIVACY.md](PRIVACY.md).

## Requirements (to build)

- JDK 17
- Android SDK with `platforms;android-34` and `build-tools;34.0.0`
- Python 3 (used by `build.sh` to add `classes.dex` to the APK)

## Build

```bash
./build.sh
```

This compiles resources with `aapt2`, compiles Java with `javac`,
dexes with `d8`, zipaligns, and signs with a debug key. Output:

```
build/expense-tracker.apk
```

The toolchain setup used originally lives outside this repo
(`~/.android-build`: Temurin JDK 17 + Android SDK 34).

## Install

1. Copy `build/expense-tracker.apk` to the phone.
2. Open it — allow **"Install unknown apps"** for the file manager/browser
   when asked.
3. If Play Protect blocks it: Play Store → profile → Play Protect →
   gear icon → turn off "Scan apps with Play Protect", install, then
   turn scanning back on.
4. Open the app and grant SMS permission when asked.

## Project structure

```
AndroidManifest.xml      Manifest (permissions: READ_SMS, RECEIVE_SMS only)
src/com/local/expensetracker/
  MainActivity.java        UI: totals, transaction list, dues section
  SmsParser.java           Bank SMS parsing & classification
  SmsReceiver.java         Live capture of incoming SMS
  Importer.java            Inbox bulk import
  DbHelper.java            SQLite storage
  Transaction.java         Transaction model
  CardDue.java             Card due model
  TxnAdapter.java          Transaction list adapter
  DueAdapter.java          Dues list adapter
res/                     Layouts, drawables, strings, themes
build.sh                 One-command build script
```

## License

Copyright (c) 2026 Chris. All rights reserved. See [LICENSE](LICENSE).

## Notes

- Signed with a debug key (fine for sideloading; generate a release
  keystore before any Play Store work).
- `minSdk 26` (Android 8.0+), `targetSdk 34`.
- This exact app would likely be rejected by Google Play's SMS permission
  policy; the Play-compliant path is the SMS User Consent API.
