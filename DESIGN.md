# Design: RescueCodec dashboard

World: a topographic map sheet in two lights. Day is paper, night is the same sheet under a red-filter lamp. Only palette, type, density and one signature move come from the map sheet. Layout and controls are standard web ones.

- Neutrals are tinted toward forest green (day `#e6eadf` ground, `#f6f7f1` panels; night `#0b1310`, `#121c17`).
- Colour has jobs: red is Mayday and critical, amber is open, blue is accepted, green is done. The Accept button is blue and the Done button is green, so a button has the colour of the state it creates.
- Type: Barlow (a DIN-style sans, the face of German signs) for UI, Barlow Condensed for headings and big numbers, JetBrains Mono for grid references and measurements. All bundled, no remote fonts.
- Signature move: the sheet edge. The map has UTM kilometre ticks on its left and bottom edge, and every report shows its UTM grid reference (for example `32U 373404 5614293`), the way rescue teams read paper maps.
- Source tags: **measured** is a solid outline with a check, **estimated** is a dashed outline with a tilde. The word is always there, colour is never the only signal.
- Scale: fixed rem scale (ratio about 1.2). Root size 15px on laptops, 17px from 1600px, 21px from 2400px. Room view adds about 30% for projectors.
- Motion: only for state. New card highlight, pulsing Mayday pin, blinking connection dot. All off under reduced motion.
