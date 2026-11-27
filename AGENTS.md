# Codex instructions

## Purpose
Maintain a universal, self-hosted Android SMS/USSD agent for `eujor/smsgateway`. Any receiver backend written in any language must work when it implements `docs/API_PROTOCOL.md`.

## Product requirements
- Native Kotlin Android app with Jetpack Compose Material 3.
- Polished, classic, colourful UI with icons and tabs: Home, Tools, Logs, Requirements; Settings accessible from the top bar.
- Settings: receiver base URL, changeable per-device API key, changeable/regeneratable UUID device ID and device name.
- Encrypt configuration secrets using Android Keystore. Never log API keys.
- Home: connection state, connect/disconnect, server test, device identity, permission status, battery optimisation helper and USSD Accessibility Service helper.
- Tools: JSON editor/validator/manual trigger. Explain JSON syntax, missing-field and type errors. A blank receiver URL must NOT block local execution; clearly warn that result will not be sent to a server.
- Tools: quick USSD test, direct USSD and chained/interactive USSD inputs.
- USSD chain: accept a JSON array of inputs and submit values one by one as prompts appear; also support server-driven `continue` actions.
- SMS send through selected SIM, receive SMS, sent callbacks and delivery reports; never equate sent with delivered.
- Logs: clearable local diagnostics; exclude API keys, message bodies and USSD input values/PINs.
- Requirements: minimum Android, permissions, JSON input/output examples, endpoint list, direct-vs-chain USSD behaviour, GitHub release URL.
- Device ID must be an app-generated UUID; do not request IMEI/IMSI.
- Server URL and API key must be editable without rebuilding.

## Engineering and security rules
- Minimum SDK 26; compile/target SDK 36 unless device compatibility research supports a deliberate change.
- JDK 17. Use stable dependencies with explicit versions.
- HTTPS only. Never disable TLS verification or allow cleartext traffic.
- Language-neutral JSON protocol. No PHP/Go-specific assumptions in the Android parser.
- Validate types, lengths, phone number, USSD code and SIM slot before telephony actions.
- Require device registration/authentication and server-side capability authorisation before remote jobs.
- Add job-ID deduplication and expiry before production release.
- Treat USSD as potentially financial/destructive. Require explicit consent for manual triggering and a secure server-side allowlist/approval model for remote jobs.
- Accessibility interaction must be limited to an active, user-authorised USSD session. Do not inspect unrelated screen content.
- Do not log SIM PINs, mobile-money PINs or chained inputs.

## Before completing a feature
1. Update `docs/API_PROTOCOL.md` for wire-contract changes.
2. Update README and Requirements UI for compatibility/permission changes.
3. Build `./gradlew assembleDebug`, run tests and resolve errors.
4. Report exactly what was built/tested. Do not claim physical-SIM validation without testing a real device.
