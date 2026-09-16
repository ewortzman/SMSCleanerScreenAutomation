# SMS Cleaner Screen Automation

Android Accessibility Service that automates bulk-deleting old messages from a single,
very long Google Messages thread, in batches, without root. Built for one specific
situation: a thread with hundreds of thousands of messages, no "jump to date" or bulk
delete-by-age feature in the app, and manual per-message deletion isn't feasible.

**This has not been built, compiled, or run.** There's no Android SDK/device available
in the environment that generated it. The overall approach (accessibility-node-based
automation, batching, dry run, session cap) is sound, but Google Messages' actual view
hierarchy (resource IDs, exact button text) is unverified -- see "Tuning" below. Expect
to iterate once on a real device.

## How it works

1. You manually scroll the target thread in Google Messages to roughly where you want
   deletion to stop (the newest message you want *deleted* should be near the top of
   the visible screen). There's no automated "find the cutoff date" logic -- that one
   scroll is on you, once, and it's cheap compared to the bulk of the backlog.
2. Hit **Start** in this app, switch to Google Messages.
3. The accessibility service polls the screen ~once a second and:
   - Long-presses the first visible message to enter selection mode.
   - Taps every unselected visible message, then scrolls up (further back in time).
   - Repeats until the selection count hits your configured **batch size** (default 1000).
   - Taps Delete, confirms the dialog, adds to the session total.
   - Repeats, walking further back through the thread's history, until it hits the top
     of the thread (oldest message) or you stop it.
4. A **session cap** (default 1000, i.e. one batch) stops the run automatically so you
   can check the phone isn't doing anything wrong before letting it run unattended for
   hours. Raise it once you trust the behavior.

## Why not root / why not just query the database

RCS conversation content generally lives in Google Messages' own private app database,
not in the standard Android SMS/MMS ContentProvider that other apps (or `adb shell
content delete ...`) can reach without root. That means screen automation via
Accessibility Service is close to the only way to touch it without rooting the phone.
See prior conversation for the root-based alternative and why it's not worth it here
(permanent Knox fuse trip, Samsung Pay/Secure Folder disabled forever, banking apps
refusing to launch).

## Setup

1. Open this folder in Android Studio (it'll offer to generate the Gradle wrapper if
   missing -- accept that, or run `gradle wrapper` yourself if you have Gradle installed).
2. Enable Developer Options + USB debugging on the phone, plug in via USB.
3. Build & install (`Run` in Android Studio, or `./gradlew installDebug`). No root needed.
4. Launch the app, tap **"Open Accessibility Settings"**, find "SMS Cleaner" in the list,
   enable it. Android will show a permissions warning (accessibility services can read
   screen content and perform actions) -- expected, that's exactly what this needs.

## Tuning (you'll almost certainly need this step)

The button text, resource IDs, and class names this app looks for
(`app/src/main/java/com/example/smscleaner/Selectors.kt`) are best-effort guesses, not
verified against a real device.

1. Open the long thread, long-press a message so the "N selected" toolbar appears.
2. In this app, tap **"Dump current screen tree to Logcat"**, then immediately switch
   back to Google Messages (the dump reads whatever's in the foreground).
3. `adb logcat -s SMSCleanerTree` and read the dumped tree: class names, resource IDs,
   text, content-descriptions, bounds, for every node on screen.
4. Compare against `Selectors.kt`. Update:
   - `RECYCLER_CLASS_FRAGMENTS` if the message list container isn't a plain RecyclerView/ListView.
   - `SELECTION_COUNT_REGEX` if the toolbar text isn't literally "N selected".
   - `DELETE_ACTION_TEXT_CANDIDATES` / `CONFIRM_BUTTON_TEXT_CANDIDATES` to match the real
     button text/content-description for the trash icon and the confirm dialog button.
   - `CHECKBOX_CLASS_FRAGMENT` if per-row selection isn't exposed as a checkbox-like node.
5. Rebuild, re-test with **dry run on** (default), watch `adb logcat -s SMSCleaner` to
   confirm it's identifying rows/selecting/scrolling sensibly before trusting it with
   real deletes.

If you're on Samsung Messages instead of Google Messages, change `TARGET_PACKAGE` in
`Selectors.kt` **and** `android:packageNames` in
`app/src/main/res/xml/accessibility_service_config.xml` to
`com.samsung.android.messaging`, then redo the tuning pass against that app's UI.

## Recommended rollout

1. Dry run (default), session cap low (default 1000). Watch logcat, confirm it's
   selecting/scrolling sensibly and *not* actually tapping delete.
2. Turn dry run off, keep session cap at 1000. Let it do one real batch. Check the
   thread in Google Messages: right messages gone, right messages kept.
3. Raise session cap (e.g. 20,000) and/or enable auto-continue once you trust it, then
   let it run for a long unattended stretch (plug in phone, keep screen on/Google
   Messages foregrounded -- accessibility polling needs the app actually in front).

## Known limitations

- **No cutoff-date detection.** You pick the starting scroll position manually. The
  automation only walks upward (further into the past) from wherever you left it.
- **Confirm-dialog detection is a heuristic** (any clickable node whose text matches
  "Delete"/"OK"/"Confirm" while not in an active selection). Could misfire if some other
  dialog with matching text appears at the wrong moment. Watch the first few batches.
- **No resume of an in-flight delete-confirm** if the service process dies between
  tapping Delete and tapping Confirm (e.g. OS kills it for memory). Rare, but if it
  happens you may need to manually dismiss a stray dialog before restarting.
- **No verified cap on selection size or confirmation that Google Messages preserves
  selection state across many screens of scrolling for a long batch** -- this is the
  main thing the "batch size 1000" rollout plan above is meant to derisk empirically.
- **Very long single thread may itself cause Google Messages to lag/ANR** while
  scrolling deep into history, independent of this app -- expect to babysit the first
  run, and possibly restart it a few times over a multi-hour/multi-day cleanup.
- Selection UI must be the actual foreground content for the loop to act -- screen-off
  or switching apps pauses it (it just waits, doesn't error).

## Stopping

Tap **Stop** in the app (takes effect within ~1s), or disable the accessibility service
in Settings for an immediate hard stop.
