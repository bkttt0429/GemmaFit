# Android Debug API

GemmaFit debug builds expose a structured, read-only adb API for phone-side
analysis state. It is intentionally separate from logcat so coach, pose,
native gate, subject selection, and local AI events can be inspected as JSON.

## Endpoints

```powershell
adb shell content read --uri content://com.gemmafit.debug/report
adb shell content read --uri content://com.gemmafit.debug/events
adb shell content read --uri content://com.gemmafit.debug/state
adb shell content delete --uri content://com.gemmafit.debug/events
```

`report` includes the latest state snapshot plus recent events. `events`
returns recent JSONL entries as an array. `state` returns only the latest
snapshot.

The provider is useful after testing phone videos in:

```text
/sdcard/Movies/GemmaFitTest/
```

Debug files are written under the app's private directory:

```text
/data/data/com.gemmafit/files/debug/gemmafit_debug_events.jsonl
/data/data/com.gemmafit/files/debug/gemmafit_debug_state.json
```

## Safety Boundary

The debug payload stores structured app decisions and short messages only. It
does not store raw video, raw images, medical claims, or free-form model memory.
