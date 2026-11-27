# EUJOR SMS & USSD Gateway

A self-hostable Android SIM agent for SMS and USSD. The Android app is designed to work with any backend implementing the documented HTTP/JSON protocol; it is not tied to Eujor hosting.

## Project status
This repository contains the Android v1.0.0 agent: Kotlin, Jetpack Compose Material 3 UI, encrypted configuration, a manual JSON request tester, local SMS and USSD execution, visible interactive USSD sessions, an encrypted SQLite request queue, duplicate protection, local clearable diagnostics, permission/setup helpers, a requirements screen, and the v1 server protocol.

The source still requires a successful CI build and physical-device validation across Android vendors, SIM configurations and mobile networks before it is production-ready. USSD accessibility behaviour varies by device and carrier.

## App areas
- **Home:** receiver connection controls, device identity, permission and battery setup.
- **Tools:** validate and trigger SMS/USSD JSON manually; quick USSD test; direct and chained USSD modes.
- **Logs:** local diagnostics with a clear button; sensitive message and input content is intentionally excluded.
- **Requirements:** Android requirements, protocol input/output samples, security notes and latest GitHub release link.
- **Settings:** receiver server URL, device API key (generate, reveal, copy and rotate), device UUID and device name.

A blank receiver URL does not block a manual local test. The app warns that the operation will run on the phone and its result will remain local rather than being sent to a receiver server.

## Build
Requirements: JDK 17, Android SDK Platform 36, Build Tools 36.0.0 and Gradle 8.13.

Open the repository root in Android Studio and sync Gradle. From a shell with Gradle 8.13 installed, build with:

```bash
gradle --no-daemon assembleDebug
```

GitHub Actions builds a debug APK and uploads it as a workflow artifact. Published builds will appear under [GitHub Releases](https://github.com/eujor/smsgateway/releases).

## Receiver server
Implement [docs/API_PROTOCOL.md](docs/API_PROTOCOL.md). The app uses HTTPS, a per-device bearer key, a stable UUID device ID, registration, heartbeat, batched job polling and result reporting. The agent stores requests in an encrypted SQLite queue, deduplicates by job and request ID, and processes telephony actions sequentially. A backend may be written in PHP, Go, Python, Node.js, Java or another language implementing the contract.

## Android limitations
- Minimum Android version: Android 8.0 (API 26), for the direct USSD request API.
- Direct USSD is a one-request/one-response operation. Android does not expose a universal API for arbitrary multi-step menu sessions.
- Interactive USSD launches the visible carrier dialog and uses the explicitly user-enabled Accessibility Service to read prompts and enter queued responses. OEM/carrier dialogs differ; some menus are button-based and cannot be automated reliably.
- SMS sending/receiving requires runtime permissions. SMS delivery reports depend on carrier support. `sent` does not mean `delivered`.
- Keep the phone powered, online and connected to the mobile network. Battery optimisation exemptions may be needed; Android still imposes background limits.
- USSD and SMS can incur charges or trigger financial transactions. Test with non-sensitive operations first.

## Security
Config (including the API key) is encrypted using Android Keystore AES-GCM. Remote connections require HTTPS with normal certificate validation. The app uses a random UUID, not IMEI/IMSI. Logs omit API keys, full message bodies and USSD input values.

See [AGENTS.md](AGENTS.md) for Codex development rules.


## Local queue and maintenance

The agent buffers up to 500 pending remote jobs in an encrypted SQLite queue. It executes one telephony operation at a time. An interactive USSD session holds the execution slot until the carrier dialog closes, the user cancels it, or the 120-second session timeout expires; only continuation or cancellation requests for that session can run meanwhile. Job IDs and request IDs are deduplicated. Operations interrupted by an app/device restart are marked failed and are not automatically replayed, avoiding accidental duplicate SMS or chargeable USSD actions. Daily maintenance after 02:00 local time removes terminal queue records older than 30 days and diagnostic logs older than 7 days. Maintenance runs on the next app or service start if the device was off at the scheduled time. SQLite identifiers are not reset or reused.

## Play Protect and sensitive permissions

Incoming SMS forwarding requires `RECEIVE_SMS`, and interactive USSD display/input uses an Accessibility Service. Google Play Protect may block an APK installed from an internet-sideloading source when these sensitive capabilities are declared. The app discloses the purpose of these capabilities and does not attempt to hide them or bypass Play Protect. See Google's [Play Protect warning guidance](https://developers.google.com/android/play-protect/warning-dev-guidance). Google Play distribution also requires a separate review of SMS/Call Log permission policy and any required declarations.


## Reliable result callback delivery

Every outgoing result is first stored in a separate encrypted SQLite outbox. The app requires an exact HTTP 200 response with JSON `{"success":true,"event_id":"<same event_id>"}` before deleting that event. It retries up to 6 times per batch, 10 seconds apart, then waits 5 minutes before another batch; restored internet connectivity triggers an immediate retry. Events are retained for up to 12 hours from creation. The outbox is independent of the 02:00 log/job maintenance and is never truncated or reset by it.

The receiver must durably store each event before acknowledging it and enforce idempotency on `event_id`. The app's delivery is at-least-once: if an acknowledgement is lost after server storage, the same event can be sent again. See [the callback acknowledgement contract](docs/API_PROTOCOL.md#12-device-result-callback-and-delivery-acknowledgement).
