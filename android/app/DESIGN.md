# Android app design

Surface: native Android, Jetpack Compose, Material 3. Mode: Operate (a tool used outdoors, in a hurry, with gloves). Familiar Material structure, brand in the theme.

## Use scene
A volunteer or rescuer in a forest or on a slope. Cold hands, gloves, bright sun or a dark night, one hand on the phone, often wet. Reports are short and urgent. Someone may be hurt. Nothing here may need two hands or fine aim.

## World
The same map sheet as the command-post dashboard (`DESIGN.md` at the repo root), so the two screens read as one product.
- Day is paper (`#E6EADF` ground, `#F6F7F1` cards). Night is the same sheet under a red-filter lamp (`#0B1310`, `#121C17`). Dark is a full scheme, not an inverted one. The phone's setting decides, and Settings can force Day or Night.
- Primary colour is plain ink (near black green by day, pale mint by night). So colour is free to carry state only: red is Mayday and critical, amber is open, blue is accepted, green is done and "good".
- Type: Barlow for text and labels, Barlow Condensed for headings and big numbers. Bundled in `res/font`, no remote fonts. Body 18 sp, nothing below 13 sp.
- Shapes: 6 to 12 dp corners. One hairline on cards, no shadows, no gradients.
- Signature moves: the **byte counter** (text bytes against coded bytes, two bars on one scale, always above the thumb controls) and the **contour rings** around the mic button that grow with the voice, like lines on a topographic sheet.
- Every number says **measured** (solid outline, a check) or **estimated** (dashed outline). The word is always there.

## Rules we hold to
- Touch targets: 48 dp at least, 56 to 72 dp for the actions that matter (Send 72, Accept and Done 56, mic 96 with its rings).
- One hand: Send and the mic sit in the bottom panel. The SOS strip sits above the navigation bar on every screen.
- SOS is hold to confirm: 2 seconds, filled by the frame clock (so "Remove animations" cannot make it instant), haptic ticks, a screen reader gets it as a long press. A tap does nothing.
- A Mayday is never hidden: a red banner under the top bar shows on every screen until it is answered.
- Colour is never the only signal: words and icons come with every state.
- Language: English by default, one tap on EN / DE in the top bar switches the whole app and the decoded text. All strings are in `res/values` and `res/values-de`. A unit test checks both files have the same keys and placeholders.
- Offline: no CDN, no remote fonts, no network call anywhere in the UI.
- With the keyboard up, the navigation bar goes away and the SOS strip rides on the keyboard, so Send stays reachable.

## Screens
Report, Actions, Feed, Stats, Radio (connect), Settings (from the top bar). Bottom navigation on phones, a rail from 600 dp wide.
