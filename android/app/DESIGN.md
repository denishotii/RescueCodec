# Android app design

Surface: native Android, Jetpack Compose, Material 3. Mode: Operate (a tool used outdoors, in a hurry, with gloves). Familiar Material structure, brand in the theme.

## Use scene
A volunteer or rescuer in a forest or on a slope. Cold hands, gloves, bright sun or a dark night, one hand on the phone, often wet. Reports are short and urgent. Someone may be hurt. Nothing here may need two hands or fine aim.

## World
An emergency tool, not a fun app: easy to read at a glance, few elements, big text, plain words, no decoration. It reads like a normal messenger, because everyone already knows how to use one.
- Plain neutral surfaces: white by day (chat ground `#F1F3F5`, bubbles and bars `#FFFFFF`), near black by night (`#0B0C0E`, `#16181B`). High contrast for sunlight. The phone's setting decides; Settings can force Day or Night.
- Colour only for meaning, with the meanings everyone knows: **red** is Mayday, SOS, urgent and errors; **amber** is open, someone must act; **green** is done, OK and connected; **blue** is accepted and in progress, and the one accent (my own bubbles, primary buttons, the selected tab); **grey** is neutral info and times. Sender names get their own six hues (purple, teal, brown, pink, slate, olive) that never look like a state.
- Type: Barlow for text and labels, Barlow Condensed for titles. Bundled in `res/font`, no remote fonts. Body 18 sp, nothing below 13 sp.
- Shapes: bubbles 18 dp with a 6 dp tail corner, cards 16 dp, buttons 10 dp. One hairline on cards from others, no shadows, no gradients.
- Our performance story stays visible but quiet: under each of my messages a grey caption such as "11 B · 5.5× smaller than text", and while typing the preview strip says "Report · Person found · 12 B (as text 38 B)".
- Every number in Airtime and savings says **measured** (solid outline, a check) or **estimated** (dashed outline). The word is always there.

## Rules we hold to
- Touch targets: 48 dp at least, 56 dp for the actions that matter (SOS, Send, mic, Accept, Done).
- One hand: SOS, the field, the mic and Send sit in one row at the bottom, where the thumb is.
- SOS is hold to confirm: 2 seconds, filled by the frame clock (so "Remove animations" cannot make it instant), haptic ticks, a screen reader gets it as a long press. A tap does nothing. It is always one hold away: at the left end of the composer on Team, a red disc at the bottom left on every other screen. While it is held, a red strip across the bottom fills and says "Keep holding", because the thumb hides the button.
- A Mayday is never hidden: a red banner under the top bar shows on every screen until it is answered.
- Colour is never the only signal: words and icons come with every state.
- Language: English by default, one tap on EN / DE in the top bar switches the whole app and the decoded text. All strings are in `res/values` and `res/values-de`. A unit test checks both files have the same keys and placeholders.
- Offline: no CDN, no remote fonts, no network call anywhere in the UI.
- With the keyboard up, the navigation bar goes away and the composer (with SOS) rides on the keyboard.
- New things are loud only when they matter. A text from the team: a notification on "Messages" (default sound, short vibration) when the app is not in front; in front, a short tone, a haptic tick, and the chat follows it or shows a "New messages" pill. A new task: "Tasks" channel, high importance. A Mayday: its own loud "Mayday" channel. Accept or Done on my own report: a gentle "Answers" notification. Never for my own messages, duplicates or retransmissions. Settings has a Sounds switch for the in-app tone.

## Screens
Two tabs in the bottom navigation (a rail from 600 dp wide): **Team** and **Tasks** (badge: open tasks and Maydays). Everything else is one tap away in the top bar.

- **Top bar** (both tabs): the channel name ("RescueCodec") with the radio status under it ("SIM-FIELD · connected", "Connecting…", "No radio · 2 waiting", with a coloured dot); tap it for the Radio screen. Then EN | DE, and a menu with Radio, Airtime and savings, Settings. The title is its own component (`ChannelTitle`), so it can open a chat list later.
- **Mayday banner**: red, under the top bar, on every screen until the Mayday is answered. Tap: Tasks. Accept or Done right in the banner.
- **Team**: one conversation, the team channel, newest at the bottom. My messages on the right in blue with status ticks (clock waiting, one tick sent, two ticks relayed, ! not confirmed or failed; the state in words when it needs attention, tap for the explanation). Others on the left with the sender's name (team label from their answers, else "Node 1a2b"). Reports (found, request, hazard, sector, mayday) are compact cards: icon, plain title, field words, grid reference and distance, task state, Accept and Done (Decline smaller, apart). A Mayday card is red. Answers are small centred lines ("Trupp 3 accepted · Person found"). Day separators. Long-press: Read aloud, Copy, Details (size, bytes on air, signal, route, Cancel or Send again). No radio: an amber line at the top, "No radio connected. Messages wait. Connect".
- **Composer**: SOS, one field ("Message or report"), the mic (hold to talk, contour rings while listening) when the field is empty, Send when there is text. While typing, a strip above the field shows how it goes out, with chips to change the type, priority or a field (bottom sheets) and "+ Add"; tap the strip to see what the others will read. Quick phrases ("Child found", "Need stretcher") show while the field is focused and empty.
- **Tasks**: Mayday first, then Open, Accepted by you, Taken by other teams, and Done folded away. Big Accept and Done.
- **Radio** (menu or status line): Bluetooth scan and pair, reconnect, Wi-Fi radio or simulator (address and port), disconnect, the channel line and picker, channel QR code and pasted link, permission prompts and hints.
- **Airtime and savings**, **Settings** (language, display, sounds, team label, radio and region, speech models, other apps, battery, about): from the menu, with a back arrow.
