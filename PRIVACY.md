# Privacy Policy — Expense Tracker

## Data stays on your phone

Expense Tracker processes everything **on the device**. There are no
servers, no accounts, no sync, and no analytics.

## Permissions used

- `READ_SMS` — to read bank SMS already in the inbox and parse
  transactions from them.
- `RECEIVE_SMS` — to capture incoming bank SMS as they arrive.
- `INTERNET` — used **only** by the self-updater (see below).
- `REQUEST_INSTALL_PACKAGES` — lets the self-updater hand a downloaded
  APK to the system installer after you approve an update.

## Self-updates (the only network use)

At most once every 48 hours — and only when you open the app — the app
asks GitHub's public releases API
(`api.github.com/repos/abrahamkarun208-png/expense-tracker/releases/latest`)
whether a newer release exists. If one does and you tap **Update**, the
APK is downloaded from `github.com` and the system installer takes over.
A "Check for updates" link at the bottom of the app forces an immediate
check.

The version check sends nothing but a plain version query. No expense
data, SMS content, or any other personal information is ever sent
anywhere — the update code has no access to your transactions.

## What the app does NOT do

- It does not share, sell, or upload SMS content, transactions, or any
  personal data to anyone.
- Parsed transactions are stored in a local SQLite database
  (`expenses.db`) in the app's private storage, which is removed if the
  app is uninstalled.

## Contact

This is a personal project. For questions about this policy, contact the
repository owner.
