# Dr. Vittu OBDII Pro v4.14.0 – universal ELM327 app (any OBD-II / EOBD car)

**Software developed by Dilip Kumar TD**

## New in v3.3.0
- Connection setup lives in Settings; auto-reconnect; EV vehicle type with battery SoC; Drive mode in-car dashboard

## v3.2.0 – advanced graphics & health
- Car Health page with top-view car diagram, status dots and health score
- Driving-profile radar, event donut, 12-week heatmap, streaks and badges
- Speed-coloured trip route, shift-light LED bar, live driving coach
- Nearby tiles (petrol, service, parking, EV, tyres, wash) and monthly PDF report

## v3.1.0 – completely redesigned interface – completely redesigned interface
- Pictograms for every function, drawn in code (no image files), and an illustrated car showing the live link status.
- New header, bottom navigation with icons, tile menus and toggle switches.
- Redesigned Connect screen. Settings are regrouped into Connection, Trips, Alerts, Driving, Auto, Custom PIDs, Data and About.

## v3.0.0
- Animated gauges with gradient arcs, redline zone, needle and peak marker. Ring gauges for coolant, fuel, battery and throttle. G-force meter and eco bar. Distance to empty.
- Alert centre with notifications for:
  - engine overheating, hot oil and catalyst temperature
  - charging fault, over-voltage, low parked battery, weak cranking
  - low fuel, refuel detected, fuel dropping while parked
  - check-engine light, new fault codes, adapter disconnected
- Smart automatic trip logging:
  - starts and stops trips with the engine
  - merges short stops into one trip
  - shows the trip live in the notification
  - can start automatically when the phone connects to the car's Bluetooth
- Performance meter: 0–60, 0–100, 60–100, ¼ mile with trap speed, and 100–0 braking distance.
- Battery monitor with a live graph and a cranking-voltage history.
- "Where's my car" parking location, plus an About screen with the version and developer credit.

Native Android app (Kotlin, no external libraries, Android 7.0+). It works with any car that has OBD-II/EOBD: petrol cars from about 2001, diesel from about 2004, and every CAN car from about 2008. That includes Tata, Maruti, Hyundai, Kia, Mahindra, Toyota, Honda, VW/Skoda, MG and others.

## Connectivity
- **Bluetooth Classic (SPP).** This covers most ELM327 v1.5/v2.1 adapters, OBDLink LX/MX and Vgate.
- **Bluetooth LE (4.0).** Works with iOS-style adapters such as Vgate iCar Pro BLE, Veepeak BLE and OBDLink CX. Supports the FFF0, FFE0 and 18F0 UART services.
- **WiFi.** The default address is 192.168.0.10:35000 and you can change it. The socket is tied to the adapter's WiFi network, so mobile data keeps working at the same time.
- **Protocols.** Auto-detects the protocol, remembers the last one that worked and falls back to CAN 500k. You can also force a protocol: CAN 11/29-bit at 250k or 500k, KWP2000, ISO 9141-2, or J1850 PWM/VPW.
- **Connection handling.**
  - Reconnects to the last adapter automatically.
  - Auto-reconnects if the link drops.
  - Detects when the ignition is off. While it's off, the app monitors the parked battery and waits for the ECU to wake.

## Features
- **Garage.** Keep several vehicles. Each one stores fuel type (petrol, diesel, E20, CNG, LPG or hybrid), engine size, tank size, fuel price, speed and RPM limits, odometer and VIN.
- **Dashboard and HUD.** Gauges plus about 20 live values. HUD mode mirrors the display for reflecting on the windscreen.
- **Live data.**
  - Every PID the ECU supports, auto-detected from about 80 decoders.
  - Tap any value to see its graph.
  - Custom mode-22 PIDs, sent to any module header, with a formula.
- **Trip computer.**
  - Starts and stops trips automatically with the engine.
  - Records distance, time, fuel, cost, CO₂, km/L or L/100 km, average and maximum values, idle time and idle fuel.
  - Times your 0–60 and 0–100 km/h runs.
  - Draws the GPS route map and exports it as GPX, KML or CSV.
- **Driving behaviour.**
  - Detects these events:
    - rapid acceleration (0.22 g)
    - hard braking (0.24 g)
    - sudden or emergency braking (0.40 g)
    - harsh cornering (0.30 g, from GPS)
    - overspeed, with time spent over the limit
    - over-rev
    - high RPM with a cold engine
    - long idle
  - Each event is logged with its location. You get optional voice or beep alerts, a driver score out of 100, and a per-trip event list.
- **Mileage.**
  - A fuel log with full-to-full real mileage and cost per km.
  - Calibrates the OBD fuel estimate against your real fill-ups.
- **Maintenance.**
  - 17 default service items such as oil, filters, plugs, coolant, brake fluid, tyres, battery, insurance and PUC. You can add your own.
  - Each item can have a km interval, a day interval, or both.
  - Shows what's due or overdue and sends background reminders every 12 hours.
  - Keeps a service history with costs and exports it to CSV.
- **Analysis.**
  - Totals for 7 days, 30 days, 90 days or all time.
  - Chart of km per day.
  - Economy and score trends.
  - Events per 100 km.
  - Speedometer error compared with GPS.
  - Suggestions based on your driving.
- **Diagnostics.**
  - Reads stored, pending and permanent fault codes from every module, with descriptions and fix hints.
  - UDS deep scan of modules 7E0–7E7.
  - Clears codes.
  - Freeze frame and readiness monitors.
  - VIN, calibration ID and ECU names.
  - Fault code history and a shareable report.
- **Health report.** Checks battery and charging, cooling and thermostat, fuel trims, idle stability, misfire hints, catalyst and boost.
- **Background and data.**
  - Runs as a foreground service, so it keeps working with the screen off.
  - Optional CSV logging to `Downloads/CarOBD`.
  - Backup and restore of all data.

## Build the APK

### Option A – GitHub (no PC software needed)
1. Create a new GitHub repository and upload the contents of this folder, keeping the folder structure (including `.github/workflows/build.yml`).
2. Open the **Actions** tab. *Build APK* runs automatically, or you can click *Run workflow*.
3. When the run turns green, open it and go to **Artifacts → CarOBD-apk**. Download and unzip it, then install `app-release.apk` on the phone (allow "install unknown apps").

If the build fails, copy the red error lines from the log and send them to me to fix.

### Option B – Android Studio
Open the folder in Android Studio (Koala or newer), let Gradle sync, then choose *Build → Build APK(s)*.

## First use
1. Plug the adapter into the OBD port under the dashboard and switch the ignition on.
2. If you have a Bluetooth Classic adapter, pair it in the phone's Bluetooth settings first (PIN 1234 or 0000). BLE and WiFi adapters don't need pairing.
3. For a WiFi adapter, join its WiFi network first.
4. Allow all permissions when asked: Nearby devices, Location (for BLE and GPS) and Notifications.
5. Go to **More → Garage** and set up your car: fuel type, engine size, tank size, fuel price and odometer.
6. On the **Connect** tab, pick the adapter and connect.

## Notes and limits
- Fuel use comes from PID 5E if the ECU supports it. Otherwise it uses MAF, and if neither exists it uses speed-density. Diesel estimates without 5E or MAF are rough, so calibrate them with one full-tank fill-up (**More → Fuel log → Calibrate**).
- ABS, airbag, BCM and other non-engine modules need manufacturer-specific commands. Engine and gearbox codes are covered by OBD and UDS.
- USB OTG adapters are not supported.
- Don't operate the app while driving.
