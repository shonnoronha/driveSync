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

## Round 2 (to run): Full Drive access, the new conflict rules, storage, skip rules, tile, log sharing, folder options and Rewind

Needs the `drive` scope on the Cloud project and `drivesync.fullDriveScope=true` (README, "Full Drive access").
Same test folder as round 1.

| # | Test | Result |
| --- | --- | --- |
| 1 | Settings → Advanced shows the switch; with the flag off it is greyed out and points to the README | to test |
| 2 | Turn on → explanation → Google consent with the "unverified app" warning → switch on | to test |
| 3 | Decline the consent (or untick Drive access) → switch stays off, "wasn't granted" shown | to test |
| 4 | Upload a file and a subfolder with a file from the Drive website → sync → both land on the phone (round 1 row 7 flips) | to test |
| 5 | Add a Google Doc on the website → skipped, its folder is kept | to test |
| 6 | Same file edited on both sides → Drive keeps its name and content, the phone version becomes `(conflict …)` on both sides | to test |
| 7 | Upload a second `notes.txt` from the website next to the synced one → Drive untouched, the phone gets `notes (conflict …).txt` | to test |
| 8 | Delete the first `notes.txt` on Drive → after two syncs the phone copy is called `notes.txt` again | to test |
| 9 | A file shared by someone else, downloads disabled → per-file error, the rest keeps syncing | to test |
| 10 | Turn off → confirm → merge-only run: nothing recycled on the phone; note any re-uploaded copies on Drive | to test |
| 11 | After turning off, are the downloaded website files still visible to the app? (decides whether the base reset stays) | to test |
| 12 | Revoke the app in the Google Account while on → folders pause with "Sign in to Google again", nothing deleted; "Ask Google again" fixes it | to test |
| 13 | A week after granting: does syncing still work, or does Testing mode make Google ask again? | to test |
| 14 | Settings shows Drive storage ("x of y used") with a bar; the row turns into a warning above 90% | to test |
| 15 | Folder → Skip files: add `*.tmp` and a 10 MB limit → new matching files stay unsynced, existing ones stay on both sides | to test |
| 16 | Remove the rule → the skipped files sync again without duplicates | to test |
| 17 | Add the "Drive Sync" Quick Settings tile → it shows "Synced … ago"; tap → "Syncing…", then back to the time | to test |
| 18 | With a folder needing attention, or on the lock screen, a tap on the tile opens the app (after unlocking) | to test |
| 19 | Activity → Share log, names hidden → the shared text shows placeholders only; turn hiding off → real names | to test |
| 20 | Folder → Include subfolders off → new files in subfolders stay unsynced, nothing is deleted; on again → they sync, no duplicates | to test |
| 21 | Folder → Wi-Fi only, phone on mobile data → automatic runs skip it; the folder's Sync now still syncs it | to test |
| 22 | Folder → Only while charging, unplugged → automatic runs skip it; plugging in lets the next automatic run sync it | to test |
| 23 | Folder → Rewind after two syncs that changed files: the slider shows the points, the preview counts match | to test |
| 24 | Edit a file, sync, rewind to before: old content on the phone and on Drive; Drive's "Manage versions" still has the newer one | to test |
| 25 | Delete a file on the phone, sync, rewind: it is back on both sides | to test |
| 26 | Add a file, sync, rewind: it is in the Drive trash and the phone recycle folder | to test |
| 27 | Edit a file without syncing, then rewind: that file is left alone and the result says so | to test |
| 28 | Rewind to the point just before the rewind: the rewind is undone | to test |
| 29 | Kill the app during a rewind: the next sync finishes it, no temp files left, nothing lost | to test |
| 30 | A folder created since the point goes away when it holds nothing else; note if real Drive leaves it empty instead | to test |
