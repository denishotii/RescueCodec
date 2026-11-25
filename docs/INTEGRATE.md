# Integrate RescueCodec in 10 minutes

This page shows how other software can send and read RescueCodec messages. Each section is self-contained.

- [Android Intent](#android-intent): another Android app asks the RescueCodec app to send a report.

## Android Intent

Use this when you have an Android app (a control app, a drone app, a note-taking app) and you want it to send a RescueCodec report over the mesh radio **without adding any library**. You send an Intent to the RescueCodec app. It queues the report, sends it over the connected radio, and returns a result.

What you need: the RescueCodec app installed on the same phone, and a radio connected to it.

What it does **not** do: it shows no screen and asks nobody for confirmation. A report goes out to the whole rescue team. Your app must let a person check the content first.

### The call

| | |
|---|---|
| Action | `org.rescuecodec.action.SEND_REPORT` |
| Component | `org.rescuecodec.app/.intent.SendReportActivity` (exported) |
| Start with | `startActivityForResult`, or the Activity Result API |
| Android 11 and newer | Add a `<queries>` entry to your manifest (see below), or the system hides our app from you. |

```xml
<!-- Your AndroidManifest.xml -->
<queries>
    <intent>
        <action android:name="org.rescuecodec.action.SEND_REPORT" />
    </intent>
</queries>
```

### Input extras

Send **one** of `JSON` or `TEXT`. All names start with `org.rescuecodec.extra.`

| Extra | Type | Meaning |
|---|---|---|
| `JSON` | String | A message in the canonical JSON shape of `spec/SPEC.md` section 4. You need no `id`: the app adds one for found, request, hazard and mayday. |
| `TEXT` | String | Free text in German or English, for example "Kind gefunden, ansprechbar, unterkühlt, braucht Trage". The rule-based extractor turns it into a message. If it cannot code the sentence safely, the report goes out as a plain text message (same as in the app). |
| `LANG` | String | `de` or `en`. Only a hint for `TEXT`. Optional. |
| `LAT`, `LON` | double | Position in degrees. Added as `pos` if the message has none. Optional. |
| `PRIORITY` | String | `critical`, `urgent`, `normal` or `routine`. Overrides the default of the message type. Optional. |
| `WAIT_SECONDS` | int | 0 to 30. If above 0, the call returns only when the report was handed to the radio, or when the time is up. Default 0 (return as soon as it is queued). |

### Result

`resultCode` is `RESULT_OK` if the report is accepted, `RESULT_CANCELED` if it is refused. The data Intent has these extras:

| Extra | Type | Meaning |
|---|---|---|
| `RESULT_JSON` | String | Everything below as one JSON object. |
| `RESULT_STATUS` | String | `queued`, `sent`, `delivered`, `not_confirmed`, `failed`, `expired`, `cancelled` or `error`. |
| `RESULT_ID` | int | The RescueCodec id of the report (0 if the type has none). Use it to follow the task. |
| `RESULT_ERROR` | String | Only on error. A stable code, see below. |

Example `RESULT_JSON` for an accepted report (the id is different every time):

```json
{"ok": true, "status": "queued", "type": "found", "id": 7, "size": 21,
 "hex": "08071a0908b8e6ea04108287583206084110011801",
 "readable": "Person found: child, conscious, hypothermia, needs stretcher. Position 50.69212, 7.21345.",
 "radio": "connected"}
```

`radio` is the link state at the time of the call: `connected`, `connecting`, `disconnected` or `error`.

`status` meanings:

- `queued`: in the send queue. It goes out when the radio is connected and the airtime budget allows. If the radio is off, it waits. Nothing is lost when the app restarts.
- `sent`: handed to the radio.
- `delivered`: another radio repeated it, so the mesh took it. This is **not** the same as "a person accepted it". The Accept arrives later as an Ack.
- `not_confirmed`: the radio sent it, but nobody repeated it. It may or may not have arrived.
- `failed`: the radio could not send it.
- `expired` or `cancelled`: it was dropped before it left the phone (too old, replaced by a newer message, or cancelled by the person).
- With `WAIT_SECONDS`, `RESULT_JSON` also has `key` (for example `a1b2c3d4-7`) and `detail` (the reason when the radio refused because of the airtime limit).

Errors (`RESULT_ERROR` and `error` in `RESULT_JSON`, with a `message` in plain English):

| Code | Meaning |
|---|---|
| `no_input` | Neither `JSON` nor `TEXT` was given. |
| `invalid_json` | `JSON` is not a JSON object. |
| `invalid_value`, `unknown_key`, `missing_field`, `no_body`, `multiple_bodies`, `too_large`, `malformed`, `empty`, `unsupported_version` | The message breaks a rule of the spec (section 5). `field` names the field, for example `found.age`. |
| `disabled` | The person turned off "Allow other apps to send reports" in the RescueCodec settings. |

### Kotlin example

```kotlin
val send = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    val json = r.data?.getStringExtra("org.rescuecodec.extra.RESULT_JSON")
    if (r.resultCode == Activity.RESULT_OK) showToast("Queued: $json")
    else showToast("Refused: $json")
}

fun sendFound() {
    val i = Intent("org.rescuecodec.action.SEND_REPORT").apply {
        setPackage("org.rescuecodec.app")
        putExtra("org.rescuecodec.extra.JSON", """
            {"found": {"age": "child", "consciousness": "conscious",
                       "injuries": ["hypothermia"], "needs": ["stretcher"]},
             "pos": {"lat": 50.69212, "lon": 7.21345}}
        """.trimIndent())
    }
    send.launch(i)
}
```

Free text instead of JSON:

```kotlin
val i = Intent("org.rescuecodec.action.SEND_REPORT")
    .setPackage("org.rescuecodec.app")
    .putExtra("org.rescuecodec.extra.TEXT", "Kind gefunden, ansprechbar, braucht Trage")
    .putExtra("org.rescuecodec.extra.LAT", 50.69212)
    .putExtra("org.rescuecodec.extra.LON", 7.21345)
    .putExtra("org.rescuecodec.extra.WAIT_SECONDS", 10)
```

### Try it from a computer

With a phone or emulator connected over `adb`:

```bash
adb shell am start -W -a org.rescuecodec.action.SEND_REPORT \
  -n org.rescuecodec.app/.intent.SendReportActivity \
  --es org.rescuecodec.extra.TEXT "Kind gefunden, ansprechbar, braucht Trage"
adb logcat -d | grep RescueCodec     # "sent found id=... 21 B" once the radio took it
```

`am start` cannot show the result extras. Use the Kotlin example for that.

### Good to know

- The first call starts the radio service if a radio is remembered. The RescueCodec app must have been opened and connected once, so Bluetooth permissions and pairing are done.
- Ids count up from a random start and wrap at 16383. Receivers ignore the same (sender, id) for 24 hours.
- The sender of the report on the mesh is the phone's radio. Your app's name is only kept in the local log.
- Everything stays on the phone and on the radio channel. The app never uses the internet.
