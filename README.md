# Fuel Receipt Generator

A free, single-file HTML tool that prints a thermal-style fuel station receipt — similar to the slip you get at an Indian Oil / HP / Bharat Petroleum pump — under your **own** station name, logo and branding. No installation, no server, no account. Everything runs in your browser and is saved on your own device.

**Live demo:** https://diliptd.github.io/fuel-receipt/

Developed by **Dilip Kumar TD**. Released as open source — free to use, copy, and modify.

---

## Features

- Auto date, time and a unique receipt ID / transaction ID on every receipt
- Fetches the fuel rate for your GPS location automatically, with a built-in table of 150+ Indian cities and states as a fallback, or an optional live price API
- Live calculation: enter either the amount (₹) or the quantity (L), and the other fills in instantly as you change the rate
- Product shown as PETROL / DIESEL / POWER PETROL / POWER DIESEL / CNG
- Captures customer name, mobile number, vehicle number and vehicle type
- CST, LST and VAT registration numbers on the receipt
- Your own logo and station details — no fixed company branding
- Paper-roll side print: an image and text strip down both edges of the receipt roll, with adjustable size, spacing and alignment
- Black & white or coloured ink option for printing
- All settings, logo and receipt history saved locally in your browser (IndexedDB) — nothing is sent to a server
- Export saved receipts as CSV, and back up/restore all settings as a JSON file
- One-time **Settings** page — set it up once, then just generate receipts

---

## Quick start

1. Open **[the live page](https://diliptd.github.io/fuel-receipt/)** in any modern browser (Chrome, Edge, Firefox), or download `index.html` from this repo and open it directly — no internet connection needed after that, except for GPS rate lookup.
2. Go to the **Settings** tab first and fill in your station details (see below).
3. Switch to the **Receipt** tab to generate and print receipts.

---

## Settings page (set up once)

| Section | What to fill in |
|---|---|
| **Logo** | Upload your station's logo image. Shown at the top of every receipt. |
| **Station details** | Station name, address, TIN, CST/LST/VAT numbers, operator name, VAT %. |
| **Footer message** | The thank-you / contact message printed at the bottom of every receipt. |
| **Date format** | Choose how the date is printed (DD/MM/YYYY, MM/DD/YYYY, etc.). |
| **Print colour** | Black & white or normal ink. |
| **Paper width** | 58 mm or 80 mm thermal roll. |
| **Font sizes** | Separate sizes for the main receipt, the numbers, and the side-print strips. |
| **Side print (left/right)** | Upload an image and/or text for each edge of the paper roll. Each side has its own: image size, rotation, alignment across the strip (left/center/right), alignment along the roll (top/center/bottom), spacing, and repeat on/off. Text supports placeholders: `{date}`, `{time}`, `{rid}`. |
| **Fuel rate source** | A city/state rate table (editable — add or correct any rate), and an optional live price API URL + key if you have one. |
| **Backup** | Export all settings as a JSON file, restore from a backup, or delete all saved data. |

All of the above is saved automatically in your browser as you type — there is no "Save" button needed on the Settings page.

---

## Generating a receipt

1. **Get my location & rate** — uses your browser's GPS permission to detect your city and automatically fill in the fuel rate (from the table or a live API, if configured).
2. Choose the **Product** (Petrol, Diesel, etc.).
3. Enter either the **Amount (₹)** or the **Quantity (L)** — the default amount is ₹1000, and changing the rate instantly recalculates the quantity (or vice versa, depending on which field you last edited).
4. Fill in customer name, mobile number, vehicle number and vehicle type (all optional).
5. Click **Generate receipt** to preview it, **Print** to print it on a thermal printer, or **Save receipt to database** to store it in your history.
6. Saved receipts appear under **Saved receipts**, where you can also **Export CSV**.

---

## Printing

- Use a thermal receipt printer (58 mm or 80 mm roll) and your browser's normal Print dialog — no special drivers needed.
- The on-screen settings panel is automatically hidden when printing; only the receipt itself prints.
- If your printer supports black & white only, turn on the **Black & white** option in Settings for a cleaner print.

---

## Data & privacy

- Everything — your logo, station details, rate table, and every saved receipt — is stored only in your own browser (IndexedDB/localStorage). Nothing is uploaded anywhere.
- GPS location is used only to look up a city name and is not stored or sent anywhere except, optionally, to a live price API you configure yourself.
- Clearing your browser's site data for this page will erase everything — use **Backup settings (JSON)** regularly if you want a copy.

---

## Running it yourself / contributing

This is a single self-contained `index.html` file — no build step, no dependencies.

1. Clone or download this repository.
2. Open `index.html` in a browser, or serve it with any static file server.
3. Edit the file directly to customise further.

Pull requests and suggestions are welcome.

---

## License

Open source — free to use, copy and modify for personal or commercial use.

---

**Software developed by Dilip Kumar TD**
