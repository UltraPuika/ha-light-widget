# HA Light – Home Assistant brightness widget for Android

A free, native Android home-screen widget that controls the brightness and on/off state of **one
Home Assistant light** without opening any app. Kotlin, no AndroidX, no third-party services, no
analytics. Only the Kotlin standard library and kotlinx-coroutines.

```
┌──────────────────────────────────────────┐
│ Living room                          (⏻) │   name + state (tap = refresh), power button
│ On                                       │
│ 65%                            (−)  (+)  │   tap % = popup slider, −/+ = 1 % steps
│ [██████████████████░░░░░░░░░░░░░░░░░░░]  │   tap anywhere on the bar = set brightness
└──────────────────────────────────────────┘
```

---

## 1. The slider: what Android allows, and what this widget does

**Android App Widgets cannot contain a draggable slider.** Widgets are drawn by the launcher
from `RemoteViews`. `RemoteViews` supports only a fixed set of views (`TextView`, `ImageView`,
`Button`, `ProgressBar`, layouts, lists and, since Android 12, `CheckBox`/`Switch`/`RadioButton`).
`SeekBar` and `Slider` are not on that list. Widgets also receive only click events, not touch or
drag events. This applies to every Android version up to and including Android 14, 15 and 16, and to
every launcher. Jetpack Glance also produces `RemoteViews`, so it has the same limits. Apps that
appear to have widget sliders use one of the workarounds below.

The widget implements the closest working alternative. All of it runs on the home screen:

| Control | Behavior |
|---|---|
| **Brightness bar** (medium and large layouts) | A `ProgressBar` covered by 10 (medium) or 20 (large) invisible tap zones. Tapping a spot on the bar sets that brightness (10 %/20 %/…/100 %, or 5 % steps on the large layout). Fill = current brightness. |
| **− / +** | ±1 % (medium/large) for exact values, ±10 % on the compact 1-row layout. Rapid taps are combined into one request. |
| **⏻ power** | Turns the light off, keeping the last brightness. Turning it on restores that brightness (`brightness_pct` = remembered value). While off, the bar shows the brightness that will be restored, in grey. |
| **Tap the percentage** | Opens a small popup over the home screen with a **real draggable slider** (1–100 %). The light follows your finger (throttled), and the final value is sent when you lift your finger. |

So 1 %–100 % is reachable directly on the widget (bar + ±1 %), and the popup gives true dragging
when you want it. The bar is never quietly turned into an "open app" button.

### Request flood protection and stale responses

* Every user action is applied to the widget immediately (optimistic UI) and passed to a
  `CoalescingSender`. It waits for **350 ms** of quiet, but sends at least every **600 ms**
  while the user keeps adjusting. Only **one request is in flight** at a time, and values
  that arrive meanwhile are merged so that only the newest is sent.
* Each action has a **sequence number**. A state read that started before the latest action,
  or arrives while an action is unsent, is discarded. A command result for an older sequence
  never clears a newer intent.
* After a command, the state is read back after ~1.2 s. Lights (especially Zigbee) often report
  the new level late. A reported state that contradicts the command is therefore ignored for a
  4 s *settle window*. After that window, the real Home Assistant state wins.
* All of this is pure Kotlin in `core/` and covered by unit tests (`WidgetModelTest`,
  `CoalescingSenderTest`).

---

## 2. Requirements and SDK choices

| | |
|---|---|
| **minSdk 31 (Android 12)** | Needed for responsive widget layouts (`RemoteViews(Map<SizeF, RemoteViews>)`), widget preview layouts, `targetCellWidth/Height`, Material You dynamic colors and the system widget corner radius. Android 14 phones are fully supported. |
| **targetSdk / compileSdk 35** | Android 15. |
| Build tools | Android Gradle Plugin 8.10.1, Kotlin 2.1.21, Gradle 8.14.3 (wrapper included), JDK 17+ (Android Studio's bundled JDK works). |
| Home Assistant | Any version with the REST API (enabled by default through `default_config` / the `http` integration). |
| Cost | €0. Uses no paid APIs and no backend. Only your Home Assistant and (optionally) Tailscale. |

---

## 3. Build

### Android Studio (recommended)

1. Install a current Android Studio (Meerkat Feature Drop 2024.3.2 or newer – any 2025/2026 release; required by AGP 8.10).
2. **File → Open…** and select this folder. Let Gradle sync. Android Studio will offer to
   install SDK Platform 35 if you don't have it.
3. Connect the phone (see §4) and press **Run ▶**, or use **Build → Build App Bundle(s)/APK(s) → Build APK(s)**.

### Command line

```bash
# Requires ANDROID_HOME (or local.properties with sdk.dir=...) and JDK 17+
./gradlew :core:test          # unit tests: API client, brightness conversion, sync rules
./gradlew :app:assembleDebug  # builds the debug APK
# → app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions

`.github/workflows/android.yml` runs the unit tests, builds the debug APK and runs Android lint on
every push, and publishes the APK as the `apk-latest` GitHub Release (direct download link above).
The free tier covers this.

---

## 4. Install the APK on the phone

**Easiest: download the ready-made APK on your phone.**
Open <https://github.com/UltraPuika/ha-light-widget/releases/download/apk-latest/ha-light.apk>
in the phone's browser and open the downloaded file. The link always points to the newest build
(CI rebuilds it on every push). All builds share one signing key (`app/debug.keystore`), so a new
version installs over the old one and keeps your settings.

Or build it yourself:

**Option A, USB with adb**

1. On the phone: *Settings → About phone →* tap **Build number** 7×, then
   *Settings → System → Developer options →* enable **USB debugging**.
2. Connect via USB and accept the prompt on the phone.
3. Run `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or press **Run** in Android Studio.

**Option B, copy the file**

1. Copy `app-debug.apk` to the phone (USB, cloud drive, etc.).
2. Open it from the Files app. When asked, allow *Install unknown apps* for that app.
3. Google Play Protect may warn about an unknown developer. Choose *Install anyway*. This
   happens because the APK is debug-signed, not from Play.

Updating: install a newer APK the same way. Builds from this project (CI or your own) are signed
with the committed `app/debug.keystore`, so they update each other. An APK signed with any other key
must be uninstalled first.

---

## 5. Set up

1. **Create a token** in Home Assistant: click your user (bottom left) → **Security** tab →
   **Long-lived access tokens → Create token**. Copy it.
   *Tip:* create a dedicated, non-admin HA user for the widget and generate the token as that user.
2. **Add the widget:** long-press the home screen → **Widgets** → **HA Light** → drag it to the
   home screen. The setup screen opens:
   * **Home Assistant URL**, for example
     * `http://192.168.1.10:8123` (LAN IP)
     * `http://homeassistant.local:8123` (mDNS, works on most Wi-Fi networks)
     * `http://100.x.y.z:8123` (Tailscale IP of the HA machine)
     * `https://homeassistant.<tailnet>.ts.net` (Tailscale HTTPS / Serve)
     * `https://ha.example.com` (your own reverse proxy)
   * **Access token**: paste it.
   * **Test connection**, then **Load lights**, pick the light (search box available) and check the
     **preview** (state, brightness, dimmable or not).
   * **Name shown on the widget** and **Background refresh** interval (off / 15 / 30 / 60 / 180 min).
   * **Save**.
3. Resize the widget as you like. The layout switches between **compact** (1 row: −, +, ⏻ and
   a thin bar), **medium** (10-zone bar) and **large** (20-zone bar).

Change settings later by long-pressing the widget → **Widget settings / pencil icon** (Android 12+
"reconfigure"), or by opening the **HA Light** app from the app drawer (connection and all placed
widgets). The URL and token are shared by all widgets. Light, name and refresh interval are per widget.

---

## 6. Security

* **Token storage:** AES-256-GCM, with the key created in and never leaving the **Android Keystore**
  (hardware-backed on most phones). Only ciphertext is stored, in app-private preferences.
  `allowBackup=false` and `data_extraction_rules.xml` exclude everything from cloud backup and
  device-to-device transfer. The key does not require unlocking, because the widget has to work
  from the background.
* **Never exposed:** the token goes only in the `Authorization: Bearer` header. It is never put
  in URLs, logs (the app does not log at all), exception messages, widget text or the settings
  screen (the field stays empty after saving: "Stored securely · leave empty to keep").
  Tests assert that error messages and `toString()` never contain the token.
* **Redirects are not followed**, so the token cannot be forwarded to another host. The user is told
  which address to enter instead.
* **TLS verification is never disabled.** Platform certificate validation is used. User-installed
  CAs are trusted so that a private CA for Home Assistant works.
* **HTTPS policy:** `http://` is accepted only for local/Tailscale addresses (RFC 1918, 100.64.0.0/10,
  link-local, `*.local`, `*.lan`, `*.home.arpa`, `*.ts.net`, single-label hosts, IPv6 ULA). Anything
  else must be `https://`. Tailscale traffic is end-to-end encrypted by WireGuard even over `http://`,
  but `https://…ts.net` (Tailscale HTTPS) is still preferred.
* **Do not expose Home Assistant directly to the internet** for this widget. Use your LAN or Tailscale.
* **Commands can only come from the widget:** the receiver for widget taps is `exported=false`,
  so other apps cannot switch your light.
* **Revoked / expired tokens:** HTTP 401/403 shows *"Token rejected · tap to fix"* on the widget.
  Tapping it opens the setup screen.
* **No analytics, ads, crash reporting, tracking or third-party services.** The only network
  traffic goes to the configured Home Assistant URL.

---

## 7. How the widget stays up to date (and its limits)

| Event | What happens |
|---|---|
| Widget added / reconfigured / settings saved | Immediate state read for all widgets |
| Any tap (bar, −/+, ⏻, popup) | Optimistic update, debounced command, read-back after ~1.2 s (and again after the settle window if needed) |
| Tap on the light name | Manual refresh |
| Background refresh | `JobScheduler` periodic job with the shortest interval chosen (min 15 min, network required, persisted across reboots, batched by Android/Doze) |
| Network error | One-off `JobScheduler` job that refreshes as soon as a network is available, with exponential backoff |
| Phone reboot / launcher restart | `onUpdate` re-renders the cached state and refreshes |

There is no permanently running service. Commands run inside the widget-tap broadcast
(`goAsync`, ≤ 9 s) and background work uses `JobScheduler`.

**Limitation (honest):** changes made *outside* the widget (wall switch, automation, HA app) are
shown at the next background refresh, your next tap, or when you tap the light name. Showing them
instantly would require a permanent WebSocket connection, and therefore a foreground service with
a permanent notification and extra battery use. That is deliberately not done. Android may also
delay periodic jobs in Doze or for apps in a low standby bucket.

---

## 8. Troubleshooting

### Connection

| Symptom | Fix |
|---|---|
| *Host not found* | Check the URL. `homeassistant.local` (mDNS) does not resolve on all networks or over Tailscale. Use the IP address or the Tailscale MagicDNS name instead. |
| *Cannot connect / No network route* | Phone on the same Wi-Fi? Correct port (`8123`)? HA running? Open the same URL in the phone's browser to compare. |
| *Did not respond in time* | HA overloaded or the route is very slow (e.g. Tailscale relayed via DERP). Retry. The widget retries automatically once the network is back. |
| *Token was rejected* | Token deleted/expired, or created for a different HA instance. Create a new long-lived token and paste it in setup. |
| *Server redirected to …* | You entered an address that redirects (e.g. http→https). Enter the address it names. |
| *Secure connection failed* | The certificate isn't trusted: self-signed or wrong hostname. Use Tailscale HTTPS / Let's Encrypt, install your own CA under *Settings → Security → Encryption & credentials → Install a certificate → CA certificate*, or use the local `http://` address. |
| *Plain http:// is only allowed for local or Tailscale addresses* | Intended. Use `https://` for public hostnames. |
| *Entity not found* | The light was renamed/removed in HA. Reconfigure the widget and pick it again. |
| Widget shows *Unavailable in Home Assistant* | HA itself reports the light as `unavailable` (e.g. Zigbee device offline / power cut at the wall). Check it in HA. |
| *On · no dimming* / grey bar | The entity only supports `onoff` color mode. Brightness controls are disabled and the power button works. |

### Tailscale

* The **Tailscale app must be installed, logged in and connected** on the phone (key icon in the
  status bar). Without a route to the HA machine the widget shows *Offline*.
* The HA machine must be in the **same tailnet**: either Tailscale on the host OS, or the
  **Tailscale add-on** for Home Assistant OS.
* Use the HA machine's Tailscale address: `http://100.x.y.z:8123` or
  `http://<machine-name>.<tailnet>.ts.net:8123` (MagicDNS must be enabled in the Tailscale admin console).
  For HTTPS, enable *HTTPS Certificates* in the tailnet and use `tailscale serve` (or the add-on's
  proxy option). Then the URL is `https://<machine-name>.<tailnet>.ts.net`.
* Android allows only one VPN at a time. Another VPN app disconnects Tailscale.
* Tailscale's **"Use Tailscale DNS"** setting must be on for MagicDNS names to resolve.
* Battery savers can stop Tailscale in the background. Set the Tailscale app's battery usage to
  *Unrestricted* if the widget is often offline when you pick up the phone.
* A good test: open the same URL in Chrome on the phone. If Chrome can't reach it, the widget can't either.

### Widget behaviour

* **Background refresh doesn't seem to happen:** set *Settings → Apps → HA Light → Battery* to
  *Unrestricted* (or "Not optimized"). Some vendors (Samsung, Xiaomi, …) restrict background
  jobs aggressively. See dontkillmyapp.com.
* **Widget not in the widget list after install:** open the app once, or restart the launcher/phone.
* **Layout doesn't change when resizing:** a few third-party launchers ignore Android 12 responsive
  layouts and always show the medium layout. All controls keep working.
* **Popup slider appears over the home screen:** this is intended. It closes when you tap
  *Done*, tap outside it, or press Back.

---

## 9. Project layout

```
core/   Pure Kotlin/JVM (no Android) – unit-tested
  Brightness.kt         0..255 ↔ % conversion, tap-zone → %, clamping
  HaClient.kt           REST client (GET /api/, /api/states[/id], POST light.turn_on/turn_off), error mapping,
                        HttpURLConnection transport (no redirects, platform TLS)
  HaUrl.kt              URL normalisation + http/https policy (LAN/Tailscale detection)
  LightState.kt         HA JSON parsing, brightness support detection (supported_color_modes)
  WidgetModel.kt        Optimistic state, sequence numbers, settle window, last-brightness restore
  CoalescingSender.kt   Debounce + throttle + single-flight sender
  ModelCodec.kt         Persisting WidgetModel
app/    Android
  widget/LightWidgetProvider     AppWidgetProvider (system broadcasts)
  widget/WidgetActionReceiver    Non-exported receiver for widget taps (goAsync)
  widget/WidgetRenderer          Responsive RemoteViews (compact / 10-zone / 20-zone)
  sync/LightController           Glue: actions → commands → read-back → render
  sync/RefreshJobs, RefreshJobService   JobScheduler periodic + retry-when-online
  data/CredentialStore           Keystore AES-GCM token storage
  data/WidgetStore               Per-widget config + state
  config/ConfigActivity          Setup screen
  config/QuickSliderActivity     Popup drag slider
```

Home Assistant API reference: <https://developers.home-assistant.io/docs/api/rest/>.
Light entity / color modes: <https://developers.home-assistant.io/docs/core/entity/light/>.

---

## 10. Test status

Automated (JVM, `./gradlew :core:test`, 63 tests):

* Brightness conversion: all 1–100 % values round-trip through HA's 0–255 scale. Low (1 %),
  medium and maximum values, clamping, tap-zone mapping, ±step.
* API handling against a fake transport *and* a real local HTTP server: correct endpoints, methods,
  JSON bodies (`entity_id`, `brightness_pct`), bearer header. 401/403/404/3xx/5xx mapping. Unknown
  host, refused connection, timeouts, TLS failures. Redirects not followed. Token never in URLs or
  messages. Entity-id validation.
* Light discovery: filtering `light.*`, sorting, dimmable detection (color modes, legacy feature bit),
  `unavailable`/`unknown`.
* Sync rules: optimistic updates, last-brightness restore after off→on, stale-fetch rejection,
  out-of-order command results, settle window, error/auth states, recovery from an orphaned intent.
* Debounce/throttle: rapid taps → one request. Continuous drag → spaced requests ending with
  the final value. No overlapping requests. Failures don't block later values.

Must be checked on a real phone (cannot be automated here): widget placement and resizing on your
launcher, touch-target comfort, behavior with your actual Zigbee lights, Tailscale on mobile data,
and background refresh under your vendor's battery management. Suggested checklist:

1. Setup with a wrong token → *rejected*. Wrong IP → *cannot connect*. Correct values → lights listed.
2. Tap the bar at the left, middle and right → ~10 %, ~50 %, 100 %. Use ±1 % to reach an exact value.
3. ⏻ off → widget shows *Off · restores N %*. ⏻ on → light returns to N %.
4. Airplane mode → tap → *Offline*. Disable airplane mode → widget recovers by itself within ~30 s–1 min.
5. Change the light from the HA app → tap the light name → widget updates.
6. Resize between 2×1, 3×2 and 4×2 → compact / medium / large layouts.
7. Revoke the token in HA → next refresh shows *Token rejected · tap to fix*.
