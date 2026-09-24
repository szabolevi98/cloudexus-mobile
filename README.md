# Cloudexus Mobile

Stock in, stock out and transfers between warehouses, by barcode, on a phone or
a handheld scanner.

The Android app of the [Cloudexus](https://github.com/szabolevi98/cloudexus)
business management system, for the people in the warehouse. The web interface
is for the office; the warehouse has barcode scanners, not browsers. The app
works through the Cloudexus API: a warehouse worker signs in with their own
username, scans the shelf and the goods, and books them with one button. The
movement shows up in the web interface at once, credited to them.

![Cloudexus Mobile: the home screen, a stock-in, a stock-out short of stock, a lookup](docs/cover.png)

Written in Kotlin with Jetpack Compose and Material 3, in the web app's colours.
Runs on Android 8.0 and later.

## What it does

### Stock movements

- **Stock in, stock out and transfers**: choose the warehouse (for a transfer,
  where from and where to), scan the lines, book. A booking carries any number of
  lines, and either all of them are booked or none is.
- **Shelf labels**: a scanned shelf code sets the location for the lines that
  follow, so a worker simply walks the shelves — shelf, item, item, next shelf.
  The shelf can also be picked from a list.
- **Scanning again**: the same product on the same shelf scanned again adds one.
  An exact quantity, fractions included, is typed in with a tap on the number.
- **Stock as it is scanned**: every scan shows how much of the product the
  warehouse has. For a stock-out or a transfer the app warns before sending when
  the list asks for more than there is.
- **Errors where they belong**: when the server refuses a booking — short of
  stock, say — the reason is shown on the line it is about ("135 in stock, 150
  asked for").

### Built for bad Wi-Fi

The Wi-Fi at the back of a warehouse drops out. When a booking goes out and the
answer does not come back, nobody can tell whether it was booked. So every
booking is sent with an
[`Idempotency-Key`](https://github.com/szabolevi98/cloudexus/blob/main/web/API.md#idempotency-key-safe-retries):

- after a send without an answer the list is locked, and one button is left:
  **Send again**;
- sending again uses the same key, so the server does not book a booking twice,
  it answers with its first answer;
- two scanners booking out the last piece at the same moment cannot both have
  it: the server queues bookings per warehouse.

### Stock lookup

Anything can be scanned without booking it: the app shows how much of it is in
which warehouse and on which shelf. A shelf with negative stock is shown in red
(more was booked out of it than into it).

### Barcode scanners

| Mode | What it is for | Setup |
|---|---|---|
| **Keyboard wedge** | Nearly every scanner does it: types the code and presses Enter | None |
| **Broadcast intent** | Works even when the scan field is not focused | Settings → Barcode scanner |
| **Camera** | Phones without a built-in scanner | The camera button in the scan field |

Broadcast intents come preset for **Zebra** (DataWedge), **Honeywell**,
**Urovo**, **Newland**, **Sunmi** and **iData**, and anything else takes its
own action and extra key. A test field on the Settings page shows at once
whether a code arrives.

> **Zebra DataWedge:** in the profile, switch *Intent output* on, set the action
> to `net.levente.cloudexus.mobile.SCAN` and the delivery to *Broadcast intent*.

The scan field is not an ordinary text field, so the on-screen keyboard does not
pop up with every scan and the scanner's Enter is not swallowed by it; the
keyboard icon switches to typing by hand. The camera reads codes with ML Kit's
bundled model, so it works on scanners without Google Play services too (EAN,
UPC, Code 128, QR and the other common formats).

### Roles

Cloudexus gives every user a role, and the app follows it. A role that may not
book stock movements sees, in place of stock in, out and transfer, why not and
who can change it; stock lookup stays. The server checks every booking anyway: a
role changed on the web shows in the app after the first booking it refuses, or
the next time the app starts.

### Security

- Everybody signs in with **their own Cloudexus account**. The server keeps only
  a hash of the token; the phone keeps it encrypted with an Android Keystore key
  that never leaves the device. The app's data is left out of backups.
- The token expires after 90 days without use, and stops working at once when
  the user is deactivated or changes their password; the app then goes back to
  the sign-in screen.
- The release build talks HTTPS only. Plain HTTP is allowed in the debug build,
  for a local server reached from the emulator (`10.0.2.2`).

### Languages

Hungarian and English, following the phone's language; from Android 13 the
app's language can be chosen on its own in the system settings. Product names
come in that language too, where the Cloudexus installation has the
translation.

## Screens

| Sign-in | Home | Stock in, scanning |
|---|---|---|
| ![Sign-in](docs/screenshots/login.png) | ![Home](docs/screenshots/home.png) | ![Stock in](docs/screenshots/stock-in.png) |

| Stock out, short of stock | Booked | Stock lookup |
|---|---|---|
| ![Stock out](docs/screenshots/stock-out-shortage.png) | ![Booked](docs/screenshots/booked.png) | ![Lookup](docs/screenshots/lookup.png) |

## Installing it

1. Download the latest `cloudexus-mobile-*.apk` from the
   [releases](https://github.com/szabolevi98/cloudexus-mobile/releases) page.
2. Install it on the phone or the scanner. The first time, Android asks to
   allow apps from unknown sources.
3. On first start, enter the Cloudexus server's address (say
   `cloudexus.example.com`), your username and your password.

It needs **Android 8.0 (API 26)** or later, and a Cloudexus server with the
mobile endpoints (`/api/auth/*`, `/api/products/lookup`,
`/api/stock/in|out|transfer`) — any version from 22 September 2026 on, with
`php database/migrate.php` run. The roles need a version from 24 September 2026;
with an older one the app hides nothing.

## Building it

| Layer | |
|---|---|
| Language, UI | Kotlin 2.4, Jetpack Compose, Material 3 in the web app's colours |
| Network | OkHttp 5 and kotlinx.serialization |
| Storage | DataStore; the token encrypted with an Android Keystore AES-GCM key |
| Camera | CameraX and ML Kit Barcode Scanning (bundled model) |
| Build | Gradle 9.7, Android Gradle Plugin 9.4, compileSdk 37, minSdk 26 |

```
./gradlew assembleDebug                 # a debug build for a local server (from the emulator: http://10.0.2.2/cloudexus/web)
./gradlew testDebugUnitTest             # the booking list's logic, and the API client against a mock server
./gradlew connectedDebugAndroidTest     # on a running emulator: the home screen for each kind of role
./gradlew assembleDebug -PminifyDebug   # a debug build shrunk by R8 exactly like the release
```

Try the camera on the `-PminifyDebug` build before a release: ML Kit's
reflection breaks only when the code is shrunk.

A signed release build needs a `keystore.properties` in the project root (never
committed):

```properties
storeFile=C:/path/to/release.jks
storePassword=...
keyAlias=cloudexus-mobile
keyPassword=...
```

Without it, `./gradlew assembleRelease` makes an unsigned APK.

### Layout

```
app/src/main/java/net/levente/cloudexus/mobile/
  data/api/        the Cloudexus API client, its models and errors
  data/session/    sign-in, the encrypted token, expiry
  data/scanner/    scanner types (broadcast intents) and receiving scans
  ui/booking/      stock in, out and transfer: the list's logic (Draft) and the screen
  ui/lookup/       stock lookup
  ui/login/, ui/home/, ui/settings/, ui/scan/ (the camera)
  ui/components/   shared pieces: header, card, scan field, quantity stepper
  ui/theme/        the web app's design tokens
app/src/test/          unit tests
app/src/androidTest/   tests on a device or emulator
```

The whole API is described in the Cloudexus repository:
[web/API.md](https://github.com/szabolevi98/cloudexus/blob/main/web/API.md).

## License

[GNU AGPLv3](LICENSE), like Cloudexus. © 2026 [szabolevi98](https://github.com/szabolevi98)
