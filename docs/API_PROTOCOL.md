# EUJOR SMS & USSD Gateway Protocol v1

This language-neutral contract connects the Android SIM agent to any self-hosted receiver server (PHP, Go, Python, Node.js, Java, etc.).

## 1. Two separate trust relationships

A compatible backend has two independent API surfaces:

1. **Requesting applications → receiver server.** A PHP application, billing platform or third-party integration authenticates with an application API key.
2. **Android agent → receiver server.** Each phone authenticates using its own device API key and device UUID.

Never reuse or expose the Android device key to a requesting application. Never let an ordinary application key authenticate as a phone. Keep separate permissions and revocation controls for both.

## 2. Transport and device identity

- HTTPS only, with normal TLS certificate and hostname validation.
- UTF-8 JSON using `application/json`.
- Every device has a unique per-device bearer API key and an app-generated UUID. The Android app can generate a cryptographically random 256-bit key locally; register that exact key for the device in the receiver server before connecting. A server may alternatively issue a key for the user to paste into the app.
- Do not use IMEI or IMSI as device identity.
- Authenticated device requests include:
  - `Authorization: Bearer <DEVICE_API_KEY>`
  - `X-Device-ID: <DEVICE_UUID>`
  - `Accept: application/json`
- Bind each key to one approved device and its allowed capabilities. Support key rotation and revocation.

## 3. Requesting application API

The backend publishes a separate API for applications that want to send SMS or initiate USSD. An application has its own key and server-side permissions.

### Submit an SMS request

`POST /api/v1/requests`

Headers:
```http
Authorization: Bearer <APPLICATION_API_KEY>
Content-Type: application/json
Idempotency-Key: order-9001-payment-receipt
```

Body:
```json
{
  "request_id": "example-request-9001",
  "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
  "type": "sms",
  "action": "send",
  "sim_slot": 1,
  "to": "254712345678",
  "message": "Your payment has been received."
}
```

### Submit a USSD request with a preloaded input chain

`POST /api/v1/requests`

```json
{
  "request_id": "balance-check-1001",
  "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
  "type": "ussd",
  "action": "execute",
  "sim_slot": 1,
  "code": "*144#",
  "inputs": ["2"]
}
```

The phone enters each string only when a subsequent USSD prompt appears. Do not concatenate all inputs into a single USSD command.

### Server-driven interactive USSD

The receiver server should expose a continuation route for the requesting application, for example:

`POST /api/v1/requests/{request_id}/continue`

```json
{
  "session_id": "ussd-session-1001",
  "input": "254712345678"
}
```

The receiver queues a `type=ussd, action=continue` job for the Android device. The agent submits that value when its active USSD dialog is ready.

To inspect a transaction, provide:

- `GET /api/v1/requests/{request_id}` — status and event history.
- `POST /api/v1/requests/{request_id}/cancel` — cancel a queued job or active session where possible.

### Acknowledgement returned to the requesting application

Return HTTP 202 when accepted into the queue:

```json
{
  "success": true,
  "request_id": "example-request-9001",
  "job_id": "job-9001",
  "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
  "status": "queued"
}
```

The request ID must be unique within the requesting application. Enforce the Idempotency-Key so retries cannot create duplicate chargeable actions.

### Results sent back to the requesting application

The receiver should retain the status and event history and, where configured, POST events to a pre-registered HTTPS callback URL. Do not accept arbitrary callback destinations from an untrusted request body.

Example callback:
```json
{
  "event_id": "evt-10001-2",
  "request_id": "example-request-9001",
  "job_id": "job-9001",
  "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
  "type": "sms",
  "status": "delivered",
  "result": {
    "recipient": "254712345678",
    "sim_slot": 1
  },
  "timestamp": "2026-11-27T14:03:00Z"
}
```

Sign callbacks with an application-specific HMAC-SHA256 secret and include a timestamp and unique event ID. The receiver and requesting application should reject stale timestamps and duplicate event IDs. The requesting application must verify the signature before trusting a callback.

## 4. Device registration

`POST /api/v1/agent/devices/register`

Authenticated with the device API key.

Input:
```json
{
  "protocol_version": "1",
  "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
  "device_name": "Office Safaricom",
  "app_version": "1.0.0-alpha01",
  "android_version": "15",
  "android_sdk": 35,
  "capabilities": ["sms.send", "sms.receive", "sms.sent_report", "sms.delivery_report", "ussd.execute", "ussd.chain", "ussd.continue"]
}
```

Expected HTTP 200/201:
```json
{
  "success": true,
  "device_status": "approved",
  "server_time": "2026-11-27T14:00:00Z",
  "heartbeat_interval": 60
}
```

The server may respond with `pending` or `revoked`; those devices must not receive jobs. Verify the device ID agrees with the key registration.

## 5. Health check

`GET /api/v1/agent/health` (authenticated with the device key). Return HTTP 200:
```json
{"success": true, "protocol_version": "1", "server_time": "2026-11-27T14:00:00Z"}
```

## 6. Device heartbeat

`POST /api/v1/agent/devices/heartbeat`
```json
{
  "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
  "device_name": "Office Safaricom",
  "app_version": "1.0.0-alpha01",
  "android_sdk": 35,
  "timestamp": "2026-11-27T14:01:00Z"
}
```

Return HTTP 200 with a JSON acknowledgement. Android sends a heartbeat approximately every 60 seconds.

## 7. Device job polling

`GET /api/v1/agent/jobs/next?device_id=<UUID>&wait_seconds=20&limit=20`

This is short HTTPS long-polling. Return HTTP 204 if there is no job, or HTTP 200:
```json
{
  "job": {
    "job_id": "job-10001",
    "request_id": "request-10001",
    "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
    "type": "sms",
    "action": "send",
    "sim_slot": 1,
    "to": "254712345678",
    "message": "Your payment has been received."
  }
}
```

Return jobs only for the authenticated device. Return either one legacy `job` object or a `jobs` array containing up to 20 jobs so Android can buffer request bursts locally. Jobs need unique IDs, expiry, idempotency keys and a lease/acknowledgement strategy. Do not blindly redeliver chargeable operations. The Android client persists requests in an encrypted SQLite queue, deduplicates by both `job_id` and `request_id`, and executes one telephony operation at a time.

## 8. SMS job

Required: `type=sms`, `action=send`, `to` (7–15 digit international number, optional leading +), `message`, `job_id`, `request_id`. Optional: `sim_slot` (1-based; defaults to 1).

The app reports separate events:
- `accepted`: Android accepted the send request.
- `sent`: Android's sent callback succeeded.
- `delivered`: a carrier delivery report was received.
- `send_failed` or `delivery_report_failed`: corresponding event failed.

A carrier may not provide delivery reports. Never treat `sent` as `delivered`.

## 9. Incoming SMS

When RECEIVE_SMS permission is granted, Android posts result type `sms.incoming` with `from`, `message` and `received_at`. The device may not identify the receiving SIM slot consistently. Store incoming messages according to the operator's data-retention policy; OTPs and banking alerts must not appear in general logs.

## 10. Direct USSD

A direct request uses `type=ussd`, `action=execute`, `code` (for example `*144#`), job/request IDs and optional 1-based `sim_slot`. With no `inputs`, the app calls Android's TelephonyManager USSD API and returns one response or a failure.

## 11. Chained / interactive USSD

To start a preloaded chain:
```json
{
  "job_id": "job-ussd-10",
  "request_id": "session-ussd-10",
  "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
  "type": "ussd",
  "action": "execute",
  "sim_slot": 1,
  "code": "*144#",
  "inputs": ["3", "254712345678"]
}
```

The app opens the visible carrier USSD dialog and, with the user-enabled Accessibility Service, enters each queued value when an editable prompt appears. Inputs are entered one at a time, not sent as one concatenated string.

For unpredictable menus:
1. Android reports `screen_updated` with `session_id` and the visible prompt text.
2. The receiver interprets the prompt and creates a `continue` job:
```json
{
  "job_id": "job-next",
  "request_id": "request-next",
  "type": "ussd",
  "action": "continue",
  "session_id": "session-ussd-10",
  "input": "2"
}
```
3. Android queues that input and submits it when the active USSD dialog presents an editable field.

Cancel with `type=ussd`, `action=cancel` and the active `session_id`. Limit to one active interactive session per SIM and expire sessions (suggested default 120 seconds). Some OEM/carrier dialogs expose only buttons or proprietary overlays; automation cannot be guaranteed on all phones.

## 12. Device result callback and delivery acknowledgement

The Android agent sends each result event to:

`POST /api/v1/agent/results`

Example request:
```json
{
  "protocol_version": "1",
  "event_id": "6d0b2c8e-9e9c-4b80-a35a-21f03c15b65c",
  "device_id": "9d6c8e7a-3b42-4f31-a2c9-8b7f12345678",
  "job_id": "job-10001",
  "request_id": "request-10001",
  "type": "sms",
  "status": "delivered",
  "result": {"recipient": "254712345678", "sim_slot": 1},
  "timestamp": "2026-11-27T14:03:00Z"
}
```

**The receiver MUST return HTTP 200 and this JSON acknowledgement:**
```json
{
  "success": true,
  "event_id": "6d0b2c8e-9e9c-4b80-a35a-21f03c15b65c"
}
```

- HTTP 202, other 2xx codes, missing/invalid JSON, `success:false`, or a different `event_id` are NOT delivery confirmation. The app keeps the event pending.
- The receiver must durably store/process the event before returning the acknowledgement.
- Treat `event_id` as an idempotency key. If the same event is received again, do not process it twice; return HTTP 200 with `success:true` and the same `event_id` after confirming the original event was stored.
- The app persists outgoing events in a separate encrypted SQLite outbox before attempting transmission. It retries failed/unconfirmed delivery up to 6 attempts per retry batch, spaced 10 seconds apart. After a batch it waits 5 minutes before another batch; validated network restoration triggers an immediate retry. It retains pending events for up to 12 hours from creation, then expires them.
- Pending callback records are separate from diagnostic logs and incoming-job records. The 02:00 maintenance task must not truncate, reset, or delete this outbox. Its identifiers are UUIDs and are never reset to 1.
- Delivery is at-least-once, not exactly-once: if the receiver stores an event but its HTTP 200 response is lost, the app will retry it. Receiver-side idempotency by `event_id` is therefore mandatory.
- A successful HTTP 200 acknowledgement confirms that the receiver accepted and durably recorded the callback event. It does NOT prove that an SMS reached its recipient or that a USSD transaction succeeded commercially.

Known statuses: `accepted`, `sent`, `delivered`, `send_failed`, `delivery_report_failed`, `completed`, `failed`, `screen_updated`, `input_submitted`, `input_queued`, `cancelled`, `received`, `timeout`.

## 13. Error response

Use meaningful HTTP status codes and:
```json
{
  "success": false,
  "error": {
    "code": "DEVICE_NOT_APPROVED",
    "message": "This device is not approved to receive jobs."
  }
}
```

Do not include secrets or sensitive request values in error bodies.

## 14. Receiver security checklist

1. TLS only; reject clear-text HTTP.
2. Keep application credentials and device credentials separate; support revocation and rotation.
3. Verify key-to-device binding on every device request.
4. Authorise capabilities separately (SMS send/receive and USSD).
5. Validate JSON types, field lengths, recipient, USSD code and SIM slot.
6. Rate-limit by requesting application and device; enforce job expiry and deduplication.
7. Authenticate result callbacks with the device key and verify job ownership.
8. Sign outgoing application callbacks with a separate per-application HMAC secret.
9. Keep audit logs but redact message bodies, OTPs, PINs and USSD input values.
10. Use pre-registered HTTPS callback destinations to prevent SSRF and callback abuse.
11. Require explicit authorisation for operations that can spend money or change accounts.
12. Never allow unauthenticated clients to trigger arbitrary SMS or USSD requests.


## 15. Android local queue, concurrency and retention

- The poll response may be either a single `job` or a `jobs` array (maximum 20). The agent stores incoming requests in an encrypted SQLite database and supports up to 500 pending jobs.
- Queue states are `queued`, `processing`, `completed`, `failed`, `timed_out`, `cancelled` and `expired`.
- Only one telephony operation is dispatched at a time. SMS holds the execution slot until Android reports `sent` or `send_failed`, with a 120-second fallback timeout if no send result arrives; a later delivery report does not hold the queue. An active USSD session holds the execution slot until its dialog closes, the user cancels, or its 120-second session timeout expires. Only `continue` and `cancel` jobs for that same session can run while the session is active. Manual USSD and SMS operations in the app use the same in-process slot guard while the gateway service is running.
- The queue enforces uniqueness for both `job_id` and `request_id`. Completed records remain available for duplicate protection for 30 days.
- If Android or the app restarts while a telephony operation is marked `processing`, the row is marked `failed` and is not replayed automatically. Automatically resending a possibly submitted SMS or repeating a chargeable USSD operation could create duplicate effects. A new request must be submitted after checking the outcome.
- Daily maintenance runs after 02:00 local device time. It deletes terminal queue records older than 30 days and diagnostic log entries older than 7 days. If the phone was off at 02:00, maintenance runs on the next app or gateway-service start. SQLite primary identifiers are not reset or reused.
- Incoming SMS forwarding includes message content in the HTTPS result because forwarding is the feature's purpose. Do not configure an untrusted receiver. General local diagnostics do not log SMS bodies, API keys or USSD input values.


## 16. Callback outbox retention and receiver requirements

Receiver implementers must follow section 12 exactly. In particular, returning HTTP 200 alone is insufficient: the JSON body must contain `success:true` and echo the submitted `event_id`. Store event IDs with a uniqueness constraint and make duplicate delivery safe. Keep deduplication records longer than the app's 12-hour retry window (24 hours or longer is recommended). Never acknowledge before the event is durably recorded.
