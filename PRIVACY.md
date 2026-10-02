# Privacy Policy — Expense Tracker

## Data stays on your phone

Expense Tracker processes everything **on the device**. There are no
servers, no accounts, no sync, and no analytics.

## Permissions used

- `READ_SMS` — to read bank SMS already in the inbox and parse
  transactions from them.
- `RECEIVE_SMS` — to capture incoming bank SMS as they arrive.

These are the **only** permissions the app requests.

## What the app does NOT do

- It does not declare the `INTERNET` permission and contains no network
  code, so it cannot transmit any data off the device.
- It does not share, sell, or upload SMS content, transactions, or any
  personal data to anyone.
- Parsed transactions are stored in a local SQLite database
  (`expenses.db`) in the app's private storage, which is removed if the
  app is uninstalled.

## Contact

This is a personal project. For questions about this policy, contact the
repository owner.
