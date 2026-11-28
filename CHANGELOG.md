# Changelog

All notable changes are documented here.

## [2.1.0] - 2026-11-28

- Reduced the Android agent's job long-poll timeout from 20 seconds to 3 seconds; the app immediately re-polls after an empty response.
- Added an in-app API map that builds copyable receiver endpoint URLs from an entered base URL and explains which component calls each route.
- Reworked the app's colour palette, card shapes and connection controls; the Home page now shows only the action appropriate to the current connection state.
- Made the app-generated device UUID read-only and copyable on Home and Settings; regeneration now requires explicit confirmation.
- Clarified in the README and protocol guide which endpoints are hosted by the receiver server, which calls them, and which optional heartbeat URL is independent.
- Updated local diagnostics to use the detected regional timezone when the SIM/network country maps unambiguously to one timezone; log timestamps include their UTC offset while protocol timestamps remain UTC.

## [2.0.0] - 2026-11-28

- Added strict USSD callback forwarding: only USSD activity initiated by the app or by server-triggered USSD jobs is reported; ordinary incoming SMS is never forwarded.
- Added a Home-screen frame listing finished USSD callbacks awaiting receiver acknowledgement, with status, job/request IDs and retry count.
- Added an individual Kill control to cancel future retries of each unconfirmed finished USSD callback.
- Improved chained USSD prompt handling to support unlabeled button-like controls and Android IME Enter where available, instead of requiring a recognised English button label.
- Retained input verification and the two-second minimum delay between successive chain submissions; failed Android actions remain queued for retry.
- Preserved the same server-issued tracking ID across execution, continuation, cancellation and result callbacks, including the X-USSD-Tracking-ID header.
- Added an independent heartbeat enable/disable switch and editable complete HTTPS heartbeat URL; heartbeat requests go only to that exact URL.
- Retained encrypted durable callback storage, independent acknowledgements, parallel delivery and retry scheduling.
- App-generated identifiers use UUIDv7; existing device IDs are preserved on upgrade.
- Standardised USSD examples on *188#.
- Final USSD callbacks now include the last visible carrier response, session_ended, end_reason and a conservative carrier_outcome classification; confirmation prompts are not reported as successful purchases.

## [1.0.0] - 2026-11-27

- Initial release with SMS sending, direct and interactive USSD, device registration, heartbeat, job polling, result callbacks, encrypted local queues and callback retry handling.
