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

1. Pick a **target date** in the app (defaults to 3 months ago). Open the target thread
   in Google Messages -- any scroll position is fine, the seek phase below finds the
   cutoff itself.
2. Hit **Start**, switch to Google Messages.
3. The accessibility service polls the screen ~once a second and runs two phases:
   - **Seek phase:** scrolls up reading Google Messages' sticky date-divider headers
     ("Today", "Yesterday", "Monday", "Jan 5", ...) until one at or before the target
     date is visible. Doesn't select or delete anything. Once done, this is remembered
     (`CleanerConfig.seekComplete`) so restarting the service doesn't repeat it.
   - **Clean phase:** long-presses the first visible message to enter selection mode,
     taps every unselected visible message, scrolls up, repeats until the selection
     count hits your configured **batch size** (default 1000), taps Delete, confirms the
     dialog, adds to the session total, and keeps going -- walking further back through
     history until it hits the top of the thread or you stop it.
4. A **session cap** (default 1000, i.e. one batch) stops the run automatically so you
   can check the phone isn't doing anything wrong before letting it run unattended for
   hours. Raise it once you trust the behavior.

Changing the target date automatically resets seek progress so the next Start re-scrolls
to the new date. There's also a manual **"Reset seek progress"** button if the seek
phase ever stops in the wrong place (see limitations below) and you want to force it to
re-scroll.

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
verified against a real device. Same for the date-divider text formats in
`DateSeek.kt`.

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
5. Do a separate dump with a date-divider header visible (scroll so one's on screen,
   dump, check the tree for the divider's exact text). Compare against `DateSeek.kt`'s
   `parse()` -- add/adjust the format patterns if the real text doesn't match "Today" /
   "Yesterday" / a full weekday name / `MMM d[, yyyy]` / `M/d/yy`.
6. Rebuild, re-test with **dry run on** (default), watch `adb logcat -s SMSCleaner` to
   confirm it's identifying rows/selecting/scrolling sensibly, and watch the seek-phase
   log lines count down toward your target date correctly, before trusting it with real
   deletes.

If you're on Samsung Messages instead of Google Messages, change `TARGET_PACKAGE` in
`Selectors.kt` **and** `android:packageNames` in
`app/src/main/res/xml/accessibility_service_config.xml` to
`com.samsung.android.messaging`, then redo the tuning pass against that app's UI.

## Recommended rollout

1. Dry run (default), session cap low (default 1000). Watch logcat, confirm the seek
   phase's date log lines look right (each one closer to the target than the last) and
   that it flips to the delete phase at roughly the right point in the thread -- *before*
   it's ever tapped a real delete.
2. Turn dry run off, keep session cap at 1000. Let it do one real batch. Check the
   thread in Google Messages: right messages gone, right messages kept, and specifically
   that it didn't delete anything newer than intended (the main risk of a seek
   false-positive -- see limitations).
3. Raise session cap (e.g. 20,000) and/or enable auto-continue once you trust it, then
   let it run for a long unattended stretch (plug in phone, keep screen on/Google
   Messages foregrounded -- accessibility polling needs the app actually in front).

## Known limitations

- **Date-divider detection is a heuristic** (see `DateSeek.kt` / tuning above) and the
  cutoff is date-level, not message-level -- the tick that flips from seeking to
  cleaning may select a few extra messages from the boundary day itself. To reduce the
  (unlikely but real) risk of a divider-lookalike string inside a message's own text
  being mistaken for a header and ending the seek phase too early, detection excludes
  any text found inside a message row and skips clickable nodes -- but this isn't
  foolproof. Watch the seek log during the dry-run pass (step 1 above) to catch a
  premature stop before it ever reaches a real delete.
- **Two seek safety valves**, both configurable in `CleanerAccessibilityService.kt`:
  stops with an error if no date-divider is ever recognized (`SEEK_NO_DATE_LIMIT`, likely
  a `DateSeek.kt` format mismatch), or treats a stalled scroll position as "reached the
  top of the thread" and proceeds to the clean phase (`SEEK_STALL_LIMIT`).
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
