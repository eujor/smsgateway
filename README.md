# EUJOR SMS & USSD Gateway

EUJOR SMS & USSD Gateway is an Android SIM-agent application that connects a phone's SIM capabilities to a self-hosted receiver server. It supports remote and manual SMS/USSD operations, interactive USSD sessions, sequential job processing, and reliable delivery of result events.

The app is backend-agnostic. A receiver can be written in PHP, Go, Python, Node.js, Java, or any language capable of implementing the documented HTTP/JSON API.

## USSD server input, device result and acknowledgement

This section is the quick integration guide for third-party receiver developers. The complete endpoint and protocol contract is in [docs/API_PROTOCOL.md](docs/API_PROTOCOL.md).

### 1. How the server sends a USSD job to the phone

The device API key is **not a JSON field**. Authenticate the HTTP request with these headers:

```http
Authorization: Bearer <DEVICE_API_KEY>
X-Device-ID: <DEVICE_UUID>
Content-Type: application/json
Accept: application/json
```

The server returns a job from the device polling endpoint. Example direct USSD job:

```json
{
  "job": {
    "job_id": "job-ussd-1001",
    "request_id": "request-ussd-1001",
    "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
    "type": "ussd",
    "action": "execute",
    "sim_slot": 1,
    "code": "*188#"
  }
}
```

Example with responses for a multi-step USSD menu:

```json
{
  "job": {
    "job_id": "job-ussd-1002",
    "request_id": "request-ussd-1002",
    "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
    "type": "ussd",
    "action": "execute",
    "sim_slot": 1,
    "code": "*188#",
    "inputs": ["1", "2", "254712345678"]
  }
}
```

Field meanings:

- `job_id`: unique ID for this device job.
- `request_id`: unique ID for the original request from the third-party application.
- `device_id`: the registered phone UUID; it must match the authenticated device.
- `type`: `ussd`.
- `action`: `execute` to start a USSD request.
- `sim_slot`: 1-based SIM slot number (`1` = SIM 1, `2` = SIM 2).
- `code`: USSD dial string, supplied as a JSON string, e.g. `"*188#"`.
- `inputs`: optional array of **strings**. Each string is entered separately when the next editable carrier prompt appears. Examples include menu choices (`"1"`), phone numbers (`"254712345678"`) or other text. Do not combine the sequence into one string. Omit `inputs` for a direct one-response USSD request.

For an interactive menu where the server must inspect each prompt before deciding what to enter, the device reports the screen text and the server sends a continuation job:

```json
{
  "job": {
    "job_id": "job-ussd-1003",
    "request_id": "request-ussd-1001",
    "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
    "type": "ussd",
    "action": "continue",
    "session_id": "active-ussd-session-id",
    "input": "2"
  }
}
```

Here, `input` is one string for the current prompt. Use `action: "cancel"` with the active `session_id` to request cancellation.

### 2. What the phone sends to the receiver after USSD execution

The Android app POSTs each result event to:

```http
POST /api/v1/agent/results
Authorization: Bearer <DEVICE_API_KEY>
X-Device-ID: <DEVICE_UUID>
Content-Type: application/json
```

A direct USSD completion event looks like this:

```json
{
  "protocol_version": "1",
  "event_id": "7a1d3c8e-9e9c-4b80-a35a-21f03c15b65c",
  "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
  "job_id": "job-ussd-1001",
  "request_id": "request-ussd-1001",
  "type": "ussd",
  "status": "completed",
  "result": {
    "code": "*188#",
    "response": "Your balance is KES 125.00",
    "sim_slot": 1
  },
  "timestamp": "2026-11-27T18:30:05Z"
}
```

For an interactive session, the phone can send several events. For example, a screen/prompt update:

```json
{
  "protocol_version": "1",
  "event_id": "6d0b2c8e-9e9c-4b80-a35a-21f03c15b65c",
  "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
  "job_id": "job-ussd-1002",
  "request_id": "request-ussd-1002",
  "type": "ussd",
  "status": "screen_updated",
  "result": {
    "session_id": "active-ussd-session-id",
    "response": "1. Check balance\n2. Buy bundle\n3. Exit"
  },
  "timestamp": "2026-11-27T18:31:00Z"
}
```

Other interactive progress statuses include `input_queued` and `input_submitted`. The final event can be `completed`, `failed`, `cancelled` or `timeout`. The app does not echo submitted input values in its progress callbacks.

**Interpret the returned text, not just the status.** `input_submitted` means Android inserted the input and attempted to submit it; it does not prove the carrier accepted it. `completed` means the USSD dialog closed or the direct request returned; it does not prove that a purchase, transfer or other carrier-side transaction succeeded. The receiver must inspect `result.response` and apply carrier-specific success/failure rules.

### 3. Confirmation the app requires from the receiver

The receiver must validate the event, **durably save it** (including its `event_id`) and only then return:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "success": true,
  "event_id": "7a1d3c8e-9e9c-4b80-a35a-21f03c15b65c"
}
```

The `event_id` in the acknowledgement must exactly match the event received. This is the app's confirmation that the receiver accepted and recorded that callback. HTTP 200 without the matching JSON body is not sufficient.

The app keeps an unconfirmed event in its separate encrypted callback outbox and retries it:

- Up to **6 attempts per retry batch**, with **10 seconds between attempts**.
- After six unsuccessful attempts, it pauses for **5 minutes** before another retry batch.
- It continues retrying while the event remains within its **12-hour retention lifetime**; after that it expires and is dropped.
- Restored validated network connectivity can trigger an immediate retry.

The same event is resent with the same `event_id`. Delivery is at-least-once, so the receiver must enforce a unique constraint/idempotency check on `event_id`. If an event was already stored, acknowledge the duplicate with the same HTTP 200 JSON response without processing it twice. Keep deduplication records for at least 24 hours.

The acknowledgement confirms **server receipt and durable logging only**. It does not confirm carrier-side USSD success. See [the full API protocol](docs/API_PROTOCOL.md#12-device-result-callback-and-delivery-acknowledgement).

## Features

### 1. SMS sending

- Send SMS through a selected SIM slot (SIM 1, SIM 2, or another supported active slot).
- Receive SMS jobs from the receiver server.
- Trigger SMS manually using the JSON tester.
- Validate recipient numbers and message content before executing a manual request.
- Report the Android send result separately from any later delivery report.
- Report these SMS states:
  - `accepted` — Android accepted the send request.
  - `sent` — Android's SMS sent callback succeeded.
  - `send_failed` — Android reported a send failure.
  - `delivered` — a carrier delivery report was received.
  - `delivery_report_failed` — a delivery report indicated failure.
- Wait for Android's send callback before the next queued telephony operation is dispatched.
- Apply a 120-second fallback timeout when Android does not return an SMS send result.

A carrier may not provide SMS delivery reports. **Sent does not mean delivered.**

### 2. Incoming SMS handling

- Ordinary incoming SMS is not read, forwarded or reported to the receiver.
- The receiver receives only results for explicit app-initiated or server-triggered gateway operations.

### 3. Direct USSD

- Execute a single USSD code using Android's `TelephonyManager.sendUssdRequest()` API.
- Select the SIM slot.
- Display the returned response in the app.
- Report the returned response or Android failure code to the receiver.

Direct USSD is a one-request/one-response operation. Android does not provide a universal public API for controlling every step of arbitrary carrier USSD menus.

### 4. Interactive and chained USSD

- Open the visible carrier USSD screen.
- Read visible USSD prompts using the app's explicitly user-enabled Accessibility Service.
- Display live USSD screen text in the app.
- Submit preloaded responses one at a time as editable prompts appear.
- Support a preloaded chain of up to 20 input values for remote jobs.
- Support server-driven continuation when the server needs to inspect a menu and decide the next input.
- Allow an active session to be cancelled.
- Report screen updates, queued inputs, submitted inputs, completion, failure, cancellation and timeout.
- Keep the active USSD session in control of the telephony slot until it closes or times out (120-second session timeout).

USSD progress statuses include:

- `screen_updated` — Android observed a changed USSD screen and reports its text.
- `input_queued` — a continuation value was accepted into the app's input queue.
- `input_submitted` — the Accessibility Service inserted the value and attempted to submit it.
- `completed` — the observed USSD dialog closed.
- `failed`, `cancelled`, `timeout` — the corresponding session end condition was reported.

The app does not echo the submitted input values in these progress events. The server already knows which values it supplied.

**USSD outcome caution:** `input_submitted` does not prove the carrier accepted the input. `completed` means the dialog closed; it does not prove a purchase, balance change, transfer or other business transaction succeeded. The final event includes `result.response` and `result.last_response` (the last visible carrier text), `session_ended`, `end_reason` and `carrier_outcome`. A response asking to “Confirm purchase” or offering Accept/Decline is marked `confirmation_required`, not success. The receiver must interpret the carrier's returned screen text. Operators may use different wording and languages. Some phone/carrier dialogs expose buttons rather than editable text fields and may not be automatable.

### 5. Receiver server connection

- Configurable HTTPS receiver base URL.
- Per-device bearer API key.
- App-generated UUID device identity; no IMEI or IMSI required.
- Configurable device name.
- Generate a random 256-bit device API key in the app.
- Reveal, hide and copy the key; generate a replacement to rotate it.
- Register the device with the receiver and check that the receiver approves it.
- Optional heartbeat with its own enable/disable switch and complete HTTPS endpoint URL; POST goes only to that exact URL, independently of the receiver server URL.
- Test the receiver server's health endpoint (the app sends a GET request to the receiver; this does not check the Android app's reachability).
- Long-poll for remote jobs.
- Receive either one job or a batch of up to 20 jobs.
- Attempt reconnection when receiver/network requests fail while the gateway service is running.

The receiver must register or otherwise recognise the same device API key entered in the app. Generating a key in the app does not automatically register it on the server.

### 6. Manual JSON tester and quick tools

The Tools screen provides:

- JSON request editor.
- JSON validation before triggering an operation.
- Manual SMS and USSD request triggering.
- Quick USSD code entry.
- SIM slot selection.
- Optional multi-line USSD input chain.
- Active USSD session cancellation.
- Latest USSD screen/response panel with its own Clear control.

A local manual test can run without a configured receiver, but its result remains on the phone and is not forwarded to a server.

### 7. Encrypted incoming-job queue

Remote jobs are persisted in an encrypted SQLite database before execution.

- Up to 500 pending jobs.
- AES-GCM encryption using Android Keystore.
- Duplicate protection by both `job_id` and `request_id`.
- Sequential SMS/USSD telephony execution.
- SMS holds the execution slot until Android reports the send result or the timeout is reached.
- An interactive USSD session holds the slot until it ends; matching continuation/cancellation jobs can still be processed.
- Manual SMS/USSD operations use the same in-process slot guard while the gateway service is running.
- Interrupted `processing` jobs are marked failed after app/service recovery and are not automatically replayed.

The app deliberately does not automatically repeat an operation that may already have reached the SIM. Replaying an SMS or a chargeable USSD action could produce duplicate effects. Check the outcome before submitting a replacement request.

### 8. Durable result-callback outbox

Every outgoing result event is stored in a **separate encrypted SQLite outbox before transmission**. This covers explicit SMS job status events and USSD progress/final events.

Each event includes a unique UUID `event_id`. The app sends events sequentially and requires this exact acknowledgement before deleting an event:

- HTTP status: **200**
- JSON body:
  ```json
  {
    "success": true,
    "event_id": "the-same-event-id-from-the-request"
  }
  ```

The app does not treat HTTP 202, another 2xx status, a missing/invalid JSON response, `success:false`, or a mismatched `event_id` as confirmation.

Retry behaviour:

- Up to **6 attempts per retry batch**.
- **10 seconds** between attempts.
- After a batch of 6 unsuccessful attempts, wait **5 minutes** before the next batch.
- When validated internet connectivity returns, pending events become eligible for an immediate retry.
- Pending events are retained for up to **12 hours from creation**, then expire.
- The callback worker is started when the app opens or the gateway service starts, so pending events can resume after an app/service restart.

Delivery is **at-least-once**, not exactly-once. For example, the receiver may store an event but its HTTP 200 response may be lost. The app will retry the same event with the same `event_id`. The receiver must deduplicate by `event_id` and return the same successful acknowledgement for a duplicate after confirming the original event was stored.

The receiver must durably store the event **before** returning HTTP 200. The acknowledgement confirms that the receiver accepted and stored the event; it does not prove SMS delivery or commercial USSD success.

See [the callback acknowledgement contract](docs/API_PROTOCOL.md#12-device-result-callback-and-delivery-acknowledgement).

### 9. App screens

| Screen | Functions |
|---|---|
| **Home** | Connect/disconnect controls, receiver status, device identity, permission indicators and setup shortcuts. |
| **Tools** | JSON editor/validator, manual SMS/USSD trigger, quick USSD, input chains and session cancellation. |
| **Logs** | Local diagnostic history, refresh and clear controls. |
| **Requirements** | Android requirements, permissions, protocol inputs/outputs, security notes and API documentation link. |
| **Settings** | Receiver URL, device API key, show/hide, copy, generate/rotate key, device name and device UUID. |

The Home screen shows setup status for runtime permissions, USSD Accessibility and battery optimisation exemption. Indicators distinguish enabled from missing permissions.

### 10. Background operation and maintenance

- The gateway runs as an Android foreground service with a persistent notification.
- The app provides a shortcut to request exemption from battery optimisation.
- Connection/job polling and the result outbox operate while their app/service process is alive.
- Android vendor battery managers and OS background limits may still stop or restrict background work. Keep the phone powered and connected.

Daily maintenance runs after 02:00 local device time:

| Data | Retention / action |
|---|---|
| Diagnostic logs | Entries older than 7 days are removed; the log view is capped at 300 entries. |
| Terminal incoming-job records | Records older than 30 days are removed. |
| Pending result callbacks | Kept for up to 12 hours from creation; never removed by the 02:00 log/job cleanup. |

The result outbox uses a separate database and UUID event identifiers. The daily maintenance task does not truncate it, reset its identifiers or delete pending callbacks. If the phone is off at 02:00, maintenance runs the next time the app or gateway service starts.

## App permissions

The minimum Android version is specified in each version's GitHub release notes and may change between releases.

- Active SIM with SMS/USSD service for the relevant operation.
- `SEND_SMS` — sending SMS.
- `CALL_PHONE` — launching interactive USSD.
- `READ_PHONE_STATE` — SIM/subscription selection.
- Notifications — foreground service notification (Android 13+ runtime permission).
- EUJOR USSD Session Assistant enabled in Android Accessibility settings — interactive USSD prompt reading and response entry.
- Internet access — receiver communication.
- Battery optimisation exemption may be needed for reliable long-running operation.

The app explains why SMS and Accessibility capabilities are used. It does not hide these permissions or attempt to bypass Android security checks.

## Installation

Download the current APK from the [EUJOR SMS & USSD Gateway releases](https://github.com/eujor/smsgateway/releases/latest). Install it on the Android phone, grant the permissions required for the features you use, enable the USSD Session Assistant for interactive/chained USSD, then configure the receiver URL and device API key in Settings.

Android may ask you to allow the browser or file manager used to open the APK to **Install unknown apps**. This is a separate Android setting from Google Play Protect. Allow the source app only when needed, and turn that permission off again after installing if you do not need it.

### Google Play Protect installation warning

Google Play Protect checks apps from outside Google Play and may warn about, or block, an app that declares sensitive capabilities such as SMS and Accessibility. Google recommends keeping Play Protect enabled.

**Only consider the following temporary setting change if you have independently verified that the APK came from the official EUJOR repository and you understand the security risk:**

1. Open the **Google Play Store**.
2. Tap your profile picture.
3. Tap **Play Protect**.
4. Tap the **Settings** gear.
5. Temporarily turn off **Scan apps with Play Protect**.
6. Install the verified APK.
7. Return to the same Play Protect settings and turn **Scan apps with Play Protect** back on immediately.

This disables Play Protect app scanning more broadly; it is not an exception limited to EUJOR SMS & USSD Gateway. Google recommends keeping it on. Disabling this setting is not a guarantee that every installation block can be overridden; device policies or other Android protections may still prevent installation. Do not install an APK from an unknown or untrusted source.

Official instructions: [Google — Use Play Protect to help keep your apps safe and your data private](https://support.google.com/android/answer/2812853).

## Receiver server integration

Implement the language-neutral contract in [docs/API_PROTOCOL.md](docs/API_PROTOCOL.md).

The Android app expects the receiver to provide:

- Device registration/approval: `POST /api/v1/agent/devices/register`.
- Receiver health check: `GET /api/v1/agent/health` is hosted by your receiver server. The Android app calls it when you use Test Connection; it checks the receiver server, not the phone/app. The app does not expose an inbound HTTP health endpoint.
- Optional heartbeat: a complete HTTPS URL configured separately in Settings; when enabled, the app POSTs to that exact URL without appending a path or using the receiver URL.
- Job polling: `GET /api/v1/agent/jobs/next`.
- Result ingestion: `POST /api/v1/agent/results`.

The receiver must authenticate the device bearer key, bind it to the registered device UUID, validate job ownership and input fields, and record result events. For result ingestion, return HTTP 200 with `{"success":true,"event_id":"<matching event_id>"}` only after the event is durably recorded. Enforce a unique constraint/idempotency rule on `event_id`; keep deduplication records longer than the app's 12-hour retry lifetime (24 hours or longer is recommended).

Keep requesting-application API keys separate from Android device API keys. Do not accept arbitrary callback URLs from untrusted request bodies. Apply authorisation, rate limits, input validation and audit logging on the receiver.

## Important operational limitations

- CI build success confirms compilation, not physical SIM/network/carrier behaviour.
- Direct USSD returns one response; interactive USSD depends on accessibility support in the phone's carrier dialog.
- USSD text is operator-specific. The receiver must interpret success/failure wording rather than treating dialog closure as proof of transaction success.
- An Accessibility Service may not recognise every OEM/carrier dialog or button layout.
- SMS delivery reports are optional carrier features.
- The app does not replay interrupted SMS/USSD jobs automatically.
- Pending result callbacks are retried while within their 12-hour lifetime, but no Android application can guarantee continued execution if the device is powered off, the app is force-stopped, its data is cleared, or the operating system prevents it from running.
- SMS and USSD operations can incur charges or trigger financial transactions. Test with non-sensitive operations first.

## Security and privacy

- Remote communication requires HTTPS with normal TLS certificate validation.
- Device API key and app configuration are encrypted using Android Keystore AES-GCM.
- Incoming jobs and outgoing callback events are encrypted at rest in separate SQLite databases.
- Device identity uses an app-generated UUID, not IMEI/IMSI.
- General diagnostic logs avoid API keys, SMS message bodies and USSD input values.
- Ordinary incoming SMS is never forwarded or transmitted.
- SMS/USSD input values may contain private or financial data. Do not store PINs or secrets in preloaded input chains unless the security implications are understood.

## Documentation

- [API protocol v1](docs/API_PROTOCOL.md)
- [GitHub repository](https://github.com/eujor/smsgateway)
- [GitHub Releases](https://github.com/eujor/smsgateway/releases)

See [AGENTS.md](AGENTS.md) for development workflow instructions.
