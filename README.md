# Cushion

A native Android app (Kotlin, Jetpack Compose) that scans for cash-secured put ideas using live Schwab market data. It reads data only and has no way to place an order.

## Get the APK

Android builds need Google Maven and the Android SDK.

**GitHub Actions (no local setup):** push this folder to a private GitHub repo. The `Build APK` workflow runs the tests, builds the app and attaches the APK to the run as `cushion-apk`.

**Android Studio:** open this folder and run the `app` configuration, or use `./gradlew :app:assembleRelease`.

Without a release key the APK is signed with a debug key. It installs fine, but each CI run generates a new debug key, so updating means uninstalling first. To keep one key, create it once:

```
keytool -genkey -v -keystore cushion.jks -alias cushion -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 cushion.jks
```

Then add four GitHub repo secrets: `CUSHION_KEYSTORE_BASE64`, `CUSHION_KEYSTORE_PASSWORD`, `CUSHION_KEY_ALIAS` and `CUSHION_KEY_PASSWORD`. Keep the `.jks` file out of the repo.

## Connect Schwab

1. At developer.schwab.com, use an app with the **Market Data Production** product.
2. On the Connect screen, enter the app key, app secret and callback URL. The URL must match the app registration exactly.
3. Either tap **Sign in with Schwab**, or paste one of these: a refresh token, a schwab-py or schwabdev token file (which also gives the exact expiry), or the URL Schwab redirected to.

Schwab refresh tokens last seven days and using them doesn't extend that, so a weekly sign-in is unavoidable. The app shows a banner for the last 48 hours when it knows the expiry.

Keys and tokens are never in the APK. They're entered on the phone, encrypted with AES-256-GCM under an Android Keystore key, and excluded from backups.

**Demo data** (on the Connect screen and in Settings) runs the app on sample numbers with no account, and labels every screen.

## Tabs

| Tab | What it does |
| --- | --- |
| Ideas | Ranked puts that pass your rules, plus Browse and search |
| Shortlist | Contracts saved with +, repriced live. Not orders |
| Exposure | What the shortlist would do to name and sector caps |
| Settings | Connection, account value, rules, symbols to scan |

## Screening rules

All of these except the regime table can be changed in Settings.

| Rule | Default |
| --- | --- |
| Target delta by VIX | under 14: 0.19, 14–20: 0.16, 20–28: 0.11 at half size, 28 and up: 0.08 at half size |
| Delta band | target ± 0.04 |
| DTE window | 7–45 days |
| Cushion | (spot − breakeven) ÷ (ATR × √DTE) at least 0.75σ, or 1.00σ for leveraged ETFs |
| Liquidity | bid at least $0.05, open interest at least 100, spread at most 15% of mid |
| Blocked | earnings before expiry, or a selloff: a drop over 1.5 ATR today, or below the 20-day average and down over 2 ATR in five sessions |
| Size | 5% of account per name, 25% per sector |

Blocked ideas stay hidden until you tap the **Blocked** filter. If the earnings calendar is unavailable, stocks are flagged **Earnings unverified** instead of looking clear.

- **Chance expires worthless** is N(d2) from the contract's IV, falling back to 1 − |delta|.
- **Premium** is the mid price.
- **Yield** is premium ÷ strike, annualised on calendar days.
- **Score** is annual yield × chance × IV/RV, with IV/RV limited to 0.5–1.5.

## Data sources

- Schwab `marketdata/v1`: quotes, chains, expirations, price history, market hours, instruments. A shared limiter holds calls to 100 a minute.
- Nasdaq's public earnings calendar, because Schwab has no earnings data. It's unofficial and could change without notice.
- Quotes are polled: every 5s in the regular session, 15s in extended hours and 60s when closed. Chains refresh every 20s during the session.

## Project layout

- `core/`: plain Kotlin with all parsing, auth, math, screening and demo data. Run its tests with `./gradlew :core:test`.
- `app/`: Android storage, networking and Compose UI.
- Figtree is bundled under the SIL Open Font License (`app/OFL-Figtree.txt`).

Nothing here is financial advice.
