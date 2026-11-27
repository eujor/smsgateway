# EUJOR SMS & USSD Gateway

A self-hostable Android SIM agent for SMS and USSD. The Android app is designed to work with any backend implementing the documented HTTP/JSON protocol; it is not tied to Eujor hosting.

## Project status
This repository contains the first Android application foundation: Kotlin, Jetpack Compose Material 3 UI, encrypted configuration, a manual JSON request tester, local SMS and USSD execution, queued USSD input chains, accessibility-assisted USSD interaction, local clearable diagnostics, permission/setup helpers, a requirements screen, and the v1 server protocol.

It still needs successful CI compilation and physical-device validation across Android vendors, SIM configurations and mobile networks before it is production-ready. USSD accessibility behaviour varies by device and carrier.

## App areas
- **Home:** receiver connection controls, device identity, permission and battery setup.
- **Tools:** validate and trigger SMS/USSD JSON manually; quick USSD test; direct and chained USSD modes.
- **Logs:** local diagnostics with a clear button; sensitive message and input content is intentionally excluded.
- **Requirements:** Android requirements, protocol input/output samples, security notes and latest GitHub release link.
- **Settings:** receiver server URL, device API key, device UUID and device name.

A blank receiver URL does not block a manual local test. The app warns that the operation will run on the phone and its result will remain local rather than being sent to a receiver server.

## Build
Requirements: JDK 17, Android SDK Platform 37, Build Tools 37.0.0 and Gradle 9.8.2.

Open the repository root in a compatible Android Studio release and sync Gradle. To build from a shell with Gradle 9.8.2 installed, run:

```bash
gradle --no-daemon assembleDebug
```

GitHub Actions builds a debug APK and uploads it as a workflow artifact. Published builds will appear under [GitHub Releases](https://github.com/eujor/smsgateway/releases).

## Receiver server
Implement [docs/API_PROTOCOL.md](docs/API_PROTOCOL.md). The app uses HTTPS, a per-device bearer key, a stable UUID device ID, registration, heartbeat, job polling and result reporting. A backend may be written in PHP, Go, Python, Node.js, Java or another language implementing the contract.

## Android limitations
- Minimum Android version: Android 8.0 (API 26), for the direct USSD request API.
- Direct USSD is a one-request/one-response operation. Android does not expose a universal API for arbitrary multi-step menu sessions.
- Chained USSD launches the visible carrier dialog and uses the explicitly user-enabled Accessibility Service to enter queued responses. OEM/carrier dialogs differ; some menus are button-based and cannot be automated reliably.
- SMS sending/receiving requires runtime permissions. SMS delivery reports depend on carrier support. `sent` does not mean `delivered`.
- Keep the phone powered, online and connected to the mobile network. Battery optimisation exemptions may be needed; Android still imposes background limits.
- USSD and SMS can incur charges or trigger financial transactions. Test with non-sensitive operations first.

## Security
Config (including the API key) is encrypted using Android Keystore AES-GCM. Remote connections require HTTPS with normal certificate validation. The app uses a random UUID, not IMEI/IMSI. Logs omit API keys, full message bodies and USSD input values.

See [AGENTS.md](AGENTS.md) for Codex development rules.
