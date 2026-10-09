# Play Console: foreground service of type "location" declaration (draft)

**Status: draft for Rahul to adapt and submit. Nothing here has been submitted, and no claim
here has been confirmed by Google Play.** Written on 2026-10-09 from the Android guide
"Foreground service types" as read that day
([ADR 0027](../adr/0027-sos-device-flow.md), "What the spike found"). The guide says that
apps targeting Android 14 or higher must declare their foreground service types in the Play
Console (Policy → App content). The exact questions of the form were **not read**; check
them when filling it in.

No secret, account id or tester's detail belongs in this file.

## What the app declares

- Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, and one service,
  `SosForegroundService`, with `android:foregroundServiceType="location"`, not exported.
- **No `ACCESS_BACKGROUND_LOCATION`.** The app has only the foreground location
  permissions (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`).

## Use-case statement (draft)

> SafeRoute is a personal-safety app. When the user starts an SOS, the app runs a foreground
> service of the type "location" for as long as that SOS lasts. The service keeps the phone's
> position coming in while the SOS screen is not in front, for example when the phone is
> locked or the user has put it away, so that the alert the phone sends to the user's
> emergency contacts carries a current position, and so that one corrected position can
> follow.
>
> The service is started only by the user starting an SOS, from a visible screen of the
> app, after a 5-second countdown that can be cancelled. It shows a notification the whole
> time ("SOS countdown" / "SOS active") with a button to call 112. It ends when the user
> cancels the countdown or taps "I'm safe". The app never starts it at boot, on a schedule
> or without the user.

## Why a foreground service, and why this type

| Question | Answer |
| --- | --- |
| Why must it continue when the app is not visible? | An SOS is started in a moment and the phone is then often locked, pocketed or dropped. The position must keep updating without the screen |
| Why not WorkManager or an alarm? | They run later and briefly, at a time the system chooses; an SOS needs the position now and continuously |
| Why "location"? | It is the type whose purpose is continuous location while the app is not visible; no other type receives positions in the background without the background-location permission |
| Is the user aware? | Yes: the user started it, a countdown preceded it, and a notification is shown the whole time |
| Can the user stop it? | Yes: Cancel during the countdown, "I'm safe" afterwards, both on the SOS screen; the notification leads there |
| How long does it run? | For the duration of one SOS |

## What the user is told

- The dialog that starts an SOS says that an SOS texts the user's emergency contacts where
  the phone is and records its positions on the phone for up to 30 days.
- The notice the user agrees to before SOS can be used
  (`docs/legal/sos-alerts-notice-v2.md`) says the same, and that the app's server receives
  neither the alerts nor the position.

## User flow to show (for a video, if one is asked for)

1. Home → the SOS control → "Hold to send SOS" for two seconds.
2. The countdown; then "SOS active".
3. Pull down the notification shade: the notification "SOS active" with "Call 112" and
   "I'm safe".
4. Press the home button, then lock the phone; unlock: the notification is still there.
5. Open the SOS screen from the notification; "I'm safe" → confirm.
6. The notification is gone; nothing is running.

## Data handling to state

- Positions collected during an SOS are stored on the device for up to 30 days and deleted
  earlier when the user signs out.
- They leave the device only inside the SMS the user's phone sends to the user's own
  emergency contacts. They are not sent to the developer's server.
- If the user has not granted a location permission, the service is not started and the SOS
  runs without positions; the app never asks for the permission during an SOS.

## Before submitting

- [ ] The form's own questions have been read and each is answered above or added here.
- [ ] The phone scripts M3 to M6 in `docs/sos/failure-matrix.md` have been run, and what
      happens to the service on the test phones is recorded.
- [ ] The privacy policy, when it exists, says the same about location.
