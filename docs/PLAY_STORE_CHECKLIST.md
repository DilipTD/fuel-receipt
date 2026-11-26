# Car OBD Pro – Google Play submission checklist

## Done in the app (v4.1.0)
- In-app Privacy Policy, Terms of Use, Data-safety summary, Permissions explained, Credits (More → Privacy & legal; Settings → Data).
- First-launch prominent disclosure + consent before any permission is requested (covers location, background use, Bluetooth, internet).
- User data controls: export (Backup), swipe-to-delete per record, "Delete all my data", "Stop location use".
- Removed the restricted REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (button now opens the system settings screen).
- Safety/medical-style disclaimers; OpenStreetMap / Open-Meteo attribution.

## You must still do (cannot be done from code)
1. **Fill in the support e-mail**: `AppInfo.SUPPORT_EMAIL` and the `[add your support e-mail here]` line in docs/*.html and *.md.
2. **Host the privacy policy at a public URL** (Play requires a URL). Easiest: publish `docs/privacy_policy.html` with GitHub Pages or any web page, paste the URL in Play Console → App content → Privacy policy.
3. **Upload an Android App Bundle (.aab)**, not an APK, signed with your own upload key (use Play App Signing). The current build is an APK signed with a debug key – fine for sideloading only.
4. **Target API level**: the app targets 34. Play requires new apps/updates to target a recent level (35 or higher); raise `targetSdk`/`compileSdk` and AGP before submitting and re-test.
5. **Developer account**: personal accounts created after Nov 2023 need a closed test (12+ testers for 14 days) before production.

## Play Console → App content answers
- **Privacy policy:** URL from step 2.
- **Ads:** No ads.
- **App access:** All functionality available without login (an OBD adapter is needed for live data – say so in the notes for reviewers; offer a demo video).
- **Content rating (IARF):** Utility/Tools, no violence/sex/gambling/user-generated content → expected Everyone/3+.
- **Target audience:** 18+ / not for children.
- **Data safety form:**
  - Does the app collect or share user data? **Collects: No / Shares: No** (data stays on device; third-party map/weather/road requests are made directly by the app for the feature the user uses – if you prefer to be conservative, declare *Location → Approximate & Precise → Collected: No, Shared: Yes (OpenStreetMap/Open-Meteo/Overpass) → purpose App functionality, optional*).
  - Data encrypted in transit: **Yes**.
  - Can users request deletion: **Yes** – in-app (Delete all my data); no account.
  - Data types processed on-device: precise location, vehicle readings, files/photos (receipts).
- **Permissions declarations:**
  - *Foreground service types* `connectedDevice` (OBD adapter connection) and `location` (trip recording / road assistant) – describe use and attach a short demo video.
  - *Location:* foreground use only (no ACCESS_BACKGROUND_LOCATION is requested; background use is via the foreground service with a notification).
  - *Bluetooth scan/connect:* find the OBD adapter.
- **Financial / health / news / government / COVID declarations:** None.

## Store listing text (copy/paste)
**Title:** Car OBD Pro – ELM327 Scanner
**Short description (≤80):** OBD-II car scanner: live gauges, trips, fuel log, fault codes & road assistant.
**Full description:**
Car OBD Pro works with ELM327 Bluetooth, BLE and Wi-Fi adapters on almost any petrol or diesel car.
• Live gauges: RPM, speed, temperatures, load, fuel trims, battery voltage
• Smart gauge cluster with on-device AI tips, eco score and trends
• Trip log with map routes, driving score and replay
• Fuel log with petrol-station location and receipt photos
• Fault-code diagnosis with root-cause suggestions and clear codes
• Road assistant: speed-limit, speed-breaker, camera and school-zone announcements
• Service reminders, garage records, battery tests, performance timing
• Backup and restore to your own Drive/OneDrive/Dropbox
• No ads, no account – your data stays on your phone
Requires an ELM327 OBD-II adapter (sold separately). Do not use the app while driving. Diagnostics are a guide only.

## Assets Play needs
512×512 icon, 1024×500 feature graphic, 2–8 phone screenshots (use the app on a device), optional 7-inch/10-inch tablet screenshots.
