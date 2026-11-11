# SwingEdge

A personal Android app for NSE swing trading: it scans the market every 15 minutes during market hours
(09:15–15:30 IST, Mon–Fri), posts BUY ideas and SELL/exit calls as phone notifications, follows up on trades you
took and health-checks your existing holdings.

It is a decision aid, not advice. Every idea shows the numbers it was tested on; losses are always possible.

## Get the app

1. On your phone, open this repository on GitHub and tap **Releases** (right side on desktop, lower on the page on mobile).
2. Open the release called **latest**.
3. Under **Assets**, tap **SwingEdge.apk** to download it.
4. Open the downloaded file. Android will ask to allow your browser or file manager to
   **install unknown apps** – allow it, go back and tap **Install**.
5. If Play Protect shows a warning (the app is not from the Play Store), choose **Install anyway**.

The link to the newest build never changes:
`https://github.com/<your-user>/<your-repo>/releases/latest/download/SwingEdge.apk`

## First-run checklist (do all three, once)

The app can only alert you on time if Android lets it run in the background. The app's settings screen shows the
status of each item and has a button for each.

1. **Allow notifications** – tap *Allow* when asked. Without this you will not see any BUY/SELL alerts.
2. **Battery: Unrestricted** – allow the app to ignore battery optimisation
   (Settings → Apps → SwingEdge → Battery → *Unrestricted*). Without this the phone delays scans while it is idle.
3. **Allow alarms** – on Android 12 and newer, turn on *Alarms & reminders* for SwingEdge
   (Settings → Apps → Special app access → Alarms & reminders). This keeps the 15-minute scans on time.

Some phone brands (Xiaomi/Redmi, Realme, Oppo, Vivo, OnePlus, Samsung) have an extra "auto-start" or
"sleeping apps" switch. If alerts arrive late, enable auto-start for SwingEdge and remove it from any
"sleeping/deep sleeping apps" list. Opening the app once after every phone restart also helps.

## Updating

Every push to `main` builds a new APK and replaces the **latest** release. To update, download `SwingEdge.apk`
again and install it – it installs **over** the old version and keeps your holdings, trades and settings.
All builds are signed with the same key (`app/swingedge-debug.jks`), which is what makes this work. If Android ever
says "App not installed" because of a signature conflict, you have a copy signed with a different key: uninstall it
once (this deletes its data) and install the new one.

## Building

Nothing to install on your computer: GitHub Actions builds the APK (`.github/workflows/build.yml`, Gradle 8.9,
JDK 17). You can also start a build by hand: **Actions → Build APK → Run workflow**. The APK is attached to the
workflow run as an artifact and published to the **latest** release.

To build locally you need JDK 17, the Android SDK (platform 34) and Gradle 8.9: `gradle assembleDebug`
(there is no Gradle wrapper in the repository).

## Layout

- `app/src/main/java/com/boomai/swingedge/core/` – all trading logic, pure Kotlin.
- `app/src/main/java/com/boomai/swingedge/` – thin Android shell (WebView, notifications, scheduling, storage, HTTP).
- `app/src/main/assets/ui/` – the user interface (offline HTML shown in a WebView).
- `SPEC.md` – the contract between the three parts.

No third-party libraries are used. If the app ever closes unexpectedly, the last error is saved to
`last_crash.txt` in the app's private storage.

Note: the signing key in this repository is public by design (convenience for a personal app). Do not reuse it
for anything you publish to an app store.
