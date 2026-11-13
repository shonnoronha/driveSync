# Device testing (build step 7)

## Round 1: 2026-11-14, Pixel 8 (Android 17, API 37), debug build

The app was installed over USB, driven partly with adb (file changes, taps, screenshots, logcat) and partly by
the owner (Google sign-in, Drive-side changes, the folder picker). The test folder was
`Documents/DriveSyncTest` on the phone and `Drive Sync/DriveSyncTest` in Drive.

| # | Test | Result |
| --- | --- | --- |
| 1 | Install and launch; theme from the wallpaper (Material You) | ✅ |
| 2 | Google sign-in (Credential Manager), `drive.file` consent, Drive Sync root created | ✅ |
| 3 | Add a folder; the first sync uploads a subfolder, two small files and a 2 MB file (resumable, chunked) | ✅ |
| 4 | A long sync runs as a `dataSync` foreground service with a notification, then stops | ✅ |
| 5 | Phone edit → re-upload; phone rename → **move on Drive** (no re-upload); phone delete → **Drive trash**; new file → upload | ✅ |
| 6 | Drive rename → phone rename (detected move); Drive trash → **phone recycle folder** | ✅ |
| 7 | A file uploaded to the folder from the Drive website is **not** seen by the app (`drive.file` scope, PRD 6.4) | ✅ as expected |
| 8 | Conflict: the same file changed on both sides → both kept (`done (conflict 2026-11-14 03-15 Pixel 8).md`) | ✅ |
| 9 | Sync when the app comes to the foreground (PRD 3.4) | ✅ |
| 10 | **Phone folder renamed away → the pair pauses ("Needs attention"); nothing is trashed on Drive** (rule 1) | ✅ |
| 11 | Recovery: "Give access again" → re-pick the folder → merge-only run → Up to date, no duplicates, no deletes | ✅ |
| 12 | Periodic background sync: scheduled every 30 min, constraints satisfied | ✅ scheduled (see notes) |

## Found and fixed
- **"Synced In 0 minutes":** the home screen read the time from a once-a-minute ticker, so a sync that had just
  finished looked like it was in the future. The time is now read fresh, and future times are clamped.
- **Lost access was not obvious:** Android **revokes an app's folder grant when the folder is renamed or moved**,
  even if it is moved back (the logs show `Permission Denial … requires that you obtain access using
  ACTION_OPEN_DOCUMENT`). The app paused correctly, but its screen suggested "Try again". Pair detail now checks
  the persisted grant, explains what happened, and puts **Give access again** first.
- **Repeated error text** ("can't be read: Access to the phone folder was lost") is now a single short reason.
- **The status chip was invisible** on the red attention card. The card is now neutral and the message
  carries the color.

## Notes and still open
- **Notifications** were not shown because the notification permission had not been granted (the onboarding
  "Allow" step was skipped). The app correctly posted nothing. To be re-tested with the permission on.
- **The periodic job can't be forced early:** `cmd jobscheduler run -f -n androidx.work.systemjobscheduler
  io.github.drivesync <id>` starts it, but WorkManager reschedules a periodic run that is not due yet
  (`reschedule = true`). It has to be checked by letting it fire on its own.
- **To do:**
  - the MediaStore photo trigger (needs a pair in DCIM or Pictures);
  - Doze and the battery-saver background delays;
  - two phones on one folder;
  - a release (R8) build on the device.
