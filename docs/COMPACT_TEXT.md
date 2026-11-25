# Compact text (word tokens)

An optional way to send ordinary chat texts in fewer bytes. Off by default.

- Every common English word is **2 bytes** (a number from a shared word list of 61,440 entries).
- A single space between two such words costs **nothing**.
- Between our own apps the text goes in a **compact frame**: 2 bytes of packaging, then the words. No envelope.
- Everything else (names, numbers, coordinates, emoji, German, typos) is copied **exactly** as raw bytes.
- The receiver gets back the **exact text**, every character, space and line break. No word is ever "corrected".

| Typed | As normal text | Before (spec 2.1) | Now (spec 2.2) |
|---|---|---|---|
| `hello world` | 11 B | 11 B (normal text: the envelope made words 13 B) | **6 B** compact |
| `We need water and blankets at the school, please hurry.` | 55 B | 27 B (words in the envelope) | **18 B** compact |
| `We found Aylin Demir at 50.6900, 7.2134, she is conscious.` | 58 B | 50 B (DEFLATE) | **45 B** compact |
| `We are at the hut, two people with us, waiting for the vehicle on the forest road` | 81 B | 41 B (words in the envelope) | **26 B** compact |
| `Ok` | 2 B | 2 B | 2 B (normal text is smaller, so it stays normal) |

Bytes are our payload. Meshtastic adds its own header (about 16 bytes, plus a few for its `Data` wrapper) to every radio packet, whatever is inside.

The wire formats are in `spec/SPEC.md`: section 3.6 (word tokens), 3.7 (compact frame) and 3.8 (domain frame). This page says how to use it, where the word lists come from, and what we measured. The team lists for special situations are in [Domain vocabularies](#domain-vocabularies-spec-23).

## Turn it on

**App:** Settings (gear) → "Use compact encoding for text messages" (German: "Kompakte Kodierung für Textnachrichten verwenden"). Default: off.

**Gateway:** `--compact-text on` (or `RESCUECODEC_COMPACT_TEXT=on`). Default: off. `GET /v2/health` shows `"compact_text": true`.

It is used only where a chat already packs texts for RescueCodec: Auto with only RescueCodec users heard, or "Always compressed". Never on a public channel, never to someone with the stock Meshtastic app. In such a chat the sender builds every form and sends the **strictly smallest**; the normal text when it is smaller or equal:

| Form | What it is |
|---|---|
| Normal text | The words as a Meshtastic text, readable by every app |
| DEFLATE (packed) | The packed text in the envelope (SPEC 3.5) |
| Word tokens in the envelope | Spec 2.1 form (SPEC 3.6), used when the text carries a GPS position |
| **Compact frame** | `0x02`, dictionary number, word tokens (SPEC 3.7). No id, no position: duplicates and delivery go by the Meshtastic packet id, like a normal text |

Sizes are whole payloads (id, position and dictionary number included).

**What the app shows.** A normal text has **no tag** (it used to say "via Meshtastic", which people read as "sent from the Meshtastic app"). A text that went packed has a small tag with its bytes on air: "compact · 6 B" (word tokens) or "packed · 42 B" (DEFLATE). German: "kompakt · 6 B", "gepackt · 42 B". Long-press → Details shows the bytes on air and as text, and the form ("Compact frame (no envelope), word dictionary 2", "Compact words, dictionary 1", "Compressed text" or "Normal Meshtastic text").

**Receiving** works always, also with the setting off: chat, notifications, gateway records, dashboard, MQTT and TAK show the text like any other.

### Try it (software, in-memory radio)

```bash
cd RescueCodec   # repo root
PYTHONPATH=. .venv/bin/python -m rescuecodec.gateway.cli --emulator --compact-text on --db :memory: --port 8051
curl -s -X PUT localhost:8051/v2/channels/RescueCodec/mode -H 'content-type: application/json' -d '{"mode":"compressed"}'
curl -s localhost:8051/v2/text -H 'content-type: application/json' \
  -d '{"text":"We are at the hut, two people with us. The path near the bridge is flooded, please use the forest road. We need water and a dry blanket."}'
```

What we got (verified **in software only**, through the real gateway code; not yet over radios):

| | |
|---|---|
| Typed text | `We are at the hut, two people with us. The path near the bridge is flooded, please use the forest road. We need water and a dry blanket.` |
| Typed size | 136 bytes |
| Sent as | compact frame, **48 bytes** on air (spec 2.1 sent word tokens in the envelope, 66 bytes; DEFLATE 70, normal text 136) |
| Recovered (`POST /v2/decode` with the sent hex) | exactly the typed text (`==` true) |

A second text with a name, a German place and coordinates, `Team 3 at Löwenburg hut. We found Aylin Demir, conscious, ankle hurt.\nMeet at 50.6900, -1.2500 in 20 min 🙂` (110 bytes): it has many names, numbers and an emoji, so DEFLATE (83 bytes) is still the smallest and the gateway sends DEFLATE; the text comes back exactly. That is the strictly-smallest rule working.

On a phone: two phones with the new APK, the same private channel (a chat that packs), the setting on in the sender. The composer's byte line shows the form, for example "Compact words · 48 B (as text 136 B)". The sent message carries the tag "compact · 48 B"; Details say "Compact frame (no envelope), word dictionary 2". With the setting on, the app no longer attaches the phone's GPS fix to free texts (reports keep it), so texts can go as compact frames. A text that carries a position (for example from another app through the intent API) goes as word tokens in the envelope (about 11 bytes more).

## Domain vocabularies (spec 2.3)

A small word list for each kind of team, so the most common words of that work cost **10 bits** instead of 2 bytes. The app and the dashboard call them "team vocabularies". There are five lists of 1016 entries each: Emergency, Search & Rescue, Firefighting, Hiking / Mountain, Medical / First aid. On the air it is a **domain frame**: `0x03`, the vocabulary id, then 10-bit codes (SPEC 3.8).

| Typed | Normal text | Compact frame | Domain frame |
|---|---|---|---|
| `Apply a tourniquet.` | 19 B | 22 B | **7 B** (Medical) |
| `Missing person found, alive.` | 28 B | 16 B | **11 B** (S&R) |
| `Patient is conscious, bleeding from the leg, apply a tourniquet.` | 64 B | 38 B | **19 B** (Medical) |
| `Fire is spreading up the ridge, need two more engines and water.` | 64 B | **24 B** | 25 B (Fire): the compact frame goes out |

- A word in the team list costs 10 bits. A word that is only in the big word list (dictionary 2) costs 26 bits: an escape code plus its 2-byte number.
- Names, numbers, coordinates and emoji are copied exactly, as before. The receiver gets the exact text back. No word is ever swapped for a similar one.
- The sender builds every form and sends the **strictly smallest**. The normal text wins a tie. So a team list never makes a message bigger.
- Every app and the gateway carry all five lists, so receiving always works, whatever the setting.
- An app from before 2.3 rejects a domain frame (unreadable-message line). It never shows wrong words. If some team members still have such an app, choose **General**.

### Turn it on

First turn on compact text (see "Turn it on" above). Then:

- **App:** Settings → "Use compact encoding for text messages" on. Below it the row **"Team vocabulary"** appears, with one chip per choice: Auto (default), General, Emergency, Search & Rescue, Firefighting, Hiking / Mountain, Medical / First aid. German: "Team-Vokabular", "Automatisch (Standard)", "Allgemein", ...
- **Gateway:** `--vocabulary auto|general|emergency|sar|fire|hiking|medical` (or `RESCUECODEC_VOCABULARY`). Default: `auto`. It only matters with `--compact-text on`. `GET /v2/health` shows `vocabulary` and the `vocabularies` with `available` (SHA-256 checked).
- **Dashboard:** no own setting; the gateway flag decides.

| Choice | What the sender tries |
|---|---|
| **Auto** (default) | every team list, plus all the normal forms |
| **General** | no team list (the spec 2.2 forms only) |
| One list, for example **Search & Rescue** ("pinned") | that list only, plus all the normal forms |

**What you see.** The message tag names the list: "compact · S&R · 11 B" (app and dashboard feed). The composer line says "Compact words · Medical · 19 B (as text 64 B)"; the dashboard send line says "Sends as: Compact words · Medical · 19 B (as text 64 B)". Long-press → Details: "Domain frame (no envelope), team vocabulary Medical / First aid, 10-bit codes" and "Without the vocabulary: 38 B as a general compact frame". In German the app and the dashboard both say "kompakt · Medizin · 19 B".

**Libraries.** Python (`rescuecodec.v2.encode_domain`), Kotlin (`RescueCodec.encodeDomain`) and TypeScript (`encodeDomain(text, "sar")`) make identical bytes. All three pass the same 108 cases in `spec/vectors/domain.json`.

### Which size, and why

A list may have 256, 512 or 1024 codes (8, 9 or 10 bits per word). The top 8 codes are control codes (escape, capital, all caps, glue, literal), so the lists have 248, 504 or 1016 entries.

We did not guess the size. For every domain we built all three sizes, each with 0 %, 10 % and 25 % two-word phrases, and measured them on held-out text (text never used to build). The score is the bytes sent divided by the typed bytes, averaged over the sources; lower is better.

| Domain | 8 bits | 9 bits | 10 bits | Phrases chosen |
|---|---|---|---|---|
| Emergency | 0.3779 | 0.3750 | **0.3694** | 10 % |
| Search & Rescue | 0.3830 | 0.3829 | **0.3791** | 10 % |
| Firefighting | 0.4161 | 0.4141 | **0.4090** | 10 % |
| Hiking / Mountain | 0.3867 | 0.3841 | **0.3791** | 25 % |
| Medical / First aid | 0.3993 | 0.3930 | **0.3837** | 10 % |

10 bits won in every domain. Bigger was always a little better, so 10 bits (the largest allowed) may leave a small saving unused. A later list can have a new id with its own width.

### Results (held-out text)

`python -I spec/tools/measure_domain.py --corpus DIR --apk dist/RescueCodec-debug.apk`. How we measured:

- **Domain sets:** 20 % of every source was kept apart by a hash of the page or block and never used to build a list. Any text that is also a build text of any list was dropped. About a third (5,041) are sentences from manuals and web pages; the rest (9,380) are disaster messages, Tatoeba sentences and our team messages.
- **General sets:** the Tatoeba test sentences and the disaster test messages that word dictionary 2 never saw, minus any text a team list was built from. Our 56 English team messages (`spec/tools/text-corpus.json`) were never used to build a list.
- Every text is at most 400 bytes and goes through the real sender code (`choose_form` in `rescuecodec/engine/textmode.py`): a chat that packs, compact text on, a 2-byte message id, no GPS position. Bytes are whole payloads.
- "Words" are whitespace-separated words. Every payload that went out was decoded again and gave the exact text (20,649 texts, settings General, Auto and pinned).

**Bytes per word (saving against the typed text).** "General" is what spec 2.2 already sent; "Auto" adds the team lists.

| Text | Texts | Words | Typed | Compact frame only | Sent, General | Sent, Auto | Auto against General |
|---|---|---|---|---|---|---|---|
| Emergency | 4,734 | 84,645 | 5.99 | 2.29 (61.8 %) | 2.29 (61.8 %) | **2.23 (62.8 %)** | 2.6 % fewer bytes |
| Search & Rescue | 1,604 | 18,179 | 5.61 | 2.21 (60.6 %) | 2.21 (60.7 %) | **2.13 (62.1 %)** | 3.7 % |
| Firefighting | 2,276 | 28,041 | 5.96 | 2.58 (56.7 %) | 2.57 (56.8 %) | **2.40 (59.7 %)** | 6.6 % |
| Hiking / Mountain | 1,846 | 20,680 | 5.56 | 2.23 (59.8 %) | 2.23 (59.8 %) | **2.15 (61.4 %)** | 3.8 % |
| Medical / First aid | 3,961 | 56,305 | 5.99 | 2.52 (58.0 %) | 2.51 (58.1 %) | **2.31 (61.4 %)** | 7.9 % |
| All five domain sets | 14,421 | 207,850 | 5.91 | | 2.37 | **2.26** | **4.9 %** |
| Everyday sentences (Tatoeba test) | 4,939 | 37,792 | 5.20 | 2.03 (60.9 %) | 2.03 (60.9 %) | 2.02 (61.2 %) | 0.8 % |
| Disaster messages (test split) | 1,233 | 26,262 | 5.94 | 2.37 (60.1 %) | 2.36 (60.3 %) | 2.35 (60.4 %) | 0.3 % |
| Our team messages (English) | 56 | 544 | 5.16 | 2.00 (61.3 %) | 2.00 (61.3 %) | 1.93 (62.6 %) | 3.6 % |

**Manual sentences against messages** (domain sets, bytes per word, General → Auto):

| Domain | Manual and web sentences | Messages |
|---|---|---|
| Emergency | 980 texts: 2.69 → 2.41, **10.5 %** fewer | 3,754 texts: 2.20 → 2.19, 0.6 % |
| Search & Rescue | 195 texts: 2.61 → 2.29, **12.2 %** | 1,409 texts: 2.13 → 2.09, 1.7 % |
| Firefighting | 1,022 texts: 2.86 → 2.57, **10.0 %** | 1,254 texts: 2.29 → 2.24, 2.5 % |
| Hiking / Mountain | 616 texts: 2.40 → 2.25, **6.3 %** | 1,230 texts: 2.13 → 2.09, 2.2 % |
| Medical / First aid | 2,228 texts: 2.77 → 2.43, **12.2 %** | 1,733 texts: 2.19 → 2.17, 0.9 % |

**What Auto sends, and does it fit one radio message (200 B)?**

| Text | Words in its list | Domain frame: own list / another list | Compact frame | DEFLATE or normal text | Smaller than with General | Fits 200 B: typed / General / Auto |
|---|---|---|---|---|---|---|
| Emergency | 51.7 % | 14.4 % / 9.5 % | 75.6 % | 0.5 % | 23.9 % | 88.2 % / 99.9 % / 99.9 % |
| Search & Rescue | 61.4 % | 21.1 % / 7.7 % | 71.1 % | 0.2 % | 28.8 % | 95.2 % / 99.9 % / 99.9 % |
| Firefighting | 60.7 % | 24.3 % / 22.0 % | 52.8 % | 1.0 % | 46.3 % | 95.3 % / 100 % / 100 % |
| Hiking / Mountain | 61.1 % | 23.8 % / 13.0 % | 62.8 % | 0.3 % | 36.8 % | 97.3 % / 100 % / 100 % |
| Medical / First aid | 60.5 % | 31.5 % / 10.1 % | 57.4 % | 1.1 % | 41.6 % | 93.6 % / 99.9 % / 99.9 % |
| Everyday sentences | 45.6 % (Emergency) | 9.2 % (any list) | 90.4 % | 0.3 % | 9.2 % | 99.5 % / 100 % / 100 % |
| Disaster messages | 47.4 % (Emergency) | 7.9 % (any list) | 90.5 % | 1.5 % | 7.9 % | 84.9 % / 100 % / 100 % |
| Our team messages | 53.7 % (Emergency) | 39.3 % (any list) | 60.7 % | 0 % | 39.3 % | 100 % / 100 % / 100 % |

**Size in the app.** The five lists are 36,472 bytes, **18,194 bytes in the APK** (zip compressed, 3.5 to 3.8 KB each). For comparison, dictionary 2 takes 277 KB.

**Honest limits.**
- **Big gain only on field-manual language.** Sentences from manuals and web pages get 6 to 12 % fewer bytes than with General. Messages in the same domains get 0.6 to 2.5 %. Everyday chat and disaster messages get under 1 %.
- **Why.** A word outside the list costs 26 bits instead of 16. So the domain frame only wins when about 10 of every 16 words (62 %) are in the list. On average 46 to 61 % are, so many texts stay compact frames.
- **One packet: no change.** With General, 99.9 to 100 % of these texts (up to 400 B) already fit one radio message. The team lists make texts a little smaller, not more of them fit.
- **Auto beats pinning.** Pinned to the right list, the domain sets got 4.3 % fewer bytes than General; Auto got 4.9 %. Auto can use a neighbouring list: many fire radio sentences fit the Emergency list better.
- **Held-out, but from the same sources.** The test text comes from other pages and blocks of the same manuals and sites, so the manual numbers may be a bit optimistic for new documents. Our own team messages (3.6 %) are the closest to real team chat, but there are only 56 of them.
- **Disaster test set.** 1,149 of the 2,357 disaster test messages were left out: the team lists were built partly from them. The 1,233 left were never used for any list.
- **English only.** German text gets nothing from these lists (the smallest-form rule keeps it as before).
- Measured in software (the real codec and sender code). Not yet over physical radios.

### Sources and licences

The lists were built by `spec/tools/domain_vocab.py` (reproducible, no randomness). All sources were retrieved on 25 Nov 2026. The URLs are in `spec/vocab/domains.yaml`.

| Source | Lists | Licence |
|---|---|---|
| Disaster Response Messages (Figure Eight / Robert Munro), English messages chosen by category; messages flagged as personal data left out | Emergency, S&R, Fire, Medical | CC BY 4.0 |
| Tatoeba English sentences, chosen by keywords per domain | all five | CC BY 2.0 FR |
| NWCG Incident Response Pocket Guide (IRPG), January 2022 | Fire | Public domain (the guide says so) |
| U.S. Fire Administration, Voice Radio Communications Guide for the Fire Service | Fire, Emergency | Public domain (U.S. federal work) |
| FEMA CERT Participant Manual, units 1 to 6 (copy on cert-la.com) | Emergency, Fire, Medical, S&R | Public domain (U.S. federal work) |
| FEMA Emergency Management Institute, ICS glossary | Emergency | Public domain (U.S. federal work) |
| U.S. Army TC 4-02.1 First Aid and ATP 4-02.11 Casualty Response | Medical | Public domain, "approved for public release" |
| National Park Service: search and rescue pages and the Guadalupe Mountains SAR plan | S&R | Public domain (nps.gov) |
| National Park Service: hiking, climbing and trip safety pages | Hiking | Public domain (nps.gov) |
| USDA Forest Service "Know before you go" pages | Hiking, S&R | Public domain (U.S. federal work) |
| NOAA National Weather Service safety pages | Emergency, Hiking, Fire | Public domain (weather.gov) |
| FEMA Ready.gov hazard pages | Emergency, Fire, Hiking | Public domain (U.S. federal work) |
| MedlinePlus health topic summaries (NLM) | Medical | Public domain (the summaries only; not the A.D.A.M. encyclopedia) |
| `spec/vocab/sar.yaml` (our own) | S&R, build only | Apache-2.0 |
| `spec/tools/text-corpus.json` team messages (our own) | test only | Apache-2.0 |

Not used: nwcg.gov, cdc.gov (NIOSH), fema.gov, uscg.mil and jcs.mil blocked scripted downloads, so we used public copies of the IRPG, the CERT manual and TC 4-02.1 instead.

Attribution: vocabularies derived in part from "Disaster Response Messages" (Figure Eight / Robert Munro, CC BY 4.0) and from sentences of Tatoeba (tatoeba.org, CC BY 2.0 FR). The other sources are U.S. federal government works (NWCG, FEMA / USFA, U.S. Army, NPS, USDA Forest Service, NOAA NWS, NLM MedlinePlus) in the public domain.

**Privacy.** The raw text stays outside the repo. We commit only the lists, the scripts and totals. An entry must be printable ASCII, appear in at least 5 build texts, and have no `@`, `http`, `www` or run of 5 or more digits. A word that is not in dictionary 2 must appear in 2 or more sources, or in 10 or more manual sentences. A word never written in lower case (a name or a heading) must appear in 2 or more sources other than Tatoeba, so Tatoeba's stock names ("Tom") are out. We read every entry that is not in dictionary 2 and found no personal names. Some web-page words ("video", "content") still use a few entries.

### Reproduce

```bash
D=/tmp/corpus3    # outside the repo; folder layout in the docstring of spec/tools/domain_vocab.py
.venv/bin/python -I spec/tools/domain_vocab.py select --corpus $D          # the size table (about 70 s)
.venv/bin/python -I spec/tools/domain_vocab.py build  --corpus $D --check  # rebuild and compare with the frozen lists
.venv/bin/python -I spec/tools/measure_domain.py --corpus $D --apk dist/RescueCodec-debug.apk   # the results above (about 5 min)
```

The frozen lists and their SHA-256 (in `spec/vocab/domains.yaml` and SPEC 3.8.7) are the reference, not the download.

## Older apps

An app or gateway from before spec 2.2 cannot read a compact frame. It **never shows wrong words**: the first byte `0x02` was reserved, so it rejects the packet as `unsupported_version`. The old app shows an unreadable-message line, the old gateway counts it as rejected. A 2.1 app that gets word tokens with dictionary 2 in the envelope shows "Message encoded with a dictionary this app does not have (word dictionary 2), please update the app". Apps before 2.1 show "Unreadable message". If some team members still have an old app: leave the setting off, or set that chat to "Always normal".

New apps read every older form forever: DEFLATE texts, word tokens with dictionary 1, and compact frames with dictionary 1 or 2.

## Long texts

- A chat that packs takes up to **400 bytes** of text. One radio message is still at most **200 bytes**.
- Other chats: 180 bytes, as before.
- If a text does not fit one radio message in any form, it is cut at the last whole character that fits, and "…" is added. The composer says "Too long: will be cut to fit one radio message". There is no multi-part message.

## Where the word list comes from

**Dictionary 2 (sent today):** `spec/vocab/words-dict-v2.txt`, **61,440 entries** (every code below the control range), 623,992 bytes, SHA-256 `c88c6b6bd08821b150a248c41f10b99c71ee5f34ab40d6840685738eb8f48dee`. 22,545 single words and punctuation pieces, 38,895 two-word phrases ("in the", "do you"). Capitalised forms of common words ("I", "We", "The", "Please", "Hello") are entries of their own, so they cost 2 bytes, not 4. Built by `spec/tools/words_dict2.py` (reproducible, no randomness). In the APK: about **277 KB** extra (zip compressed). The gateway and the TypeScript library bundle the same file, checked by SHA-256.

**Dictionary 1 (spec 2.1):** `spec/vocab/words-dict-v1.txt`, 8192 entries, 66,607 bytes, SHA-256 `ffe93afb4623fcd364479169281ca745cc263317a4f83ce27f77d8d37b26a0d8`. Built by `spec/tools/words_dict.py`. Still bundled and readable forever.

| Source | What we used | Licence | OK to ship a word list? |
|---|---|---|---|
| [Disaster response messages](https://github.com/rmunro/disaster_response_messages) (Robert Munro, Figure Eight) | the English `message` column, its own train / validation / test split | CC BY 4.0 | Yes, with attribution (this page) |
| [Tatoeba](https://tatoeba.org/en/downloads) English sentences (`eng_sentences.tsv`) | all English sentences, split 90 / 5 / 5 by a hash of the text | CC BY 2.0 FR | Yes, with attribution (this page) |
| [SCOWL](http://wordlist.aspell.net/) 2020.12.07 (Kevin Atkinson) | dictionary 2 only: English, American and British word, contraction and abbreviation lists, sizes 10 to 70, as a **filter** (no word is added from it) | Permissive: use, copy, modify, distribute and sell, keep the copyright notice | Yes, with the notice (below) |
| `spec/vocab/sar.yaml` (our own) | the English and German rescue words | Apache-2.0 | Yes |
| [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) | **not used**: its content is CC BY-SA 4.0 (share-alike) and comes from OpenSubtitles | CC BY-SA 4.0 | Would force share-alike on the word list; left out |

Attribution: word lists derived from "Disaster Response Messages" (Figure Eight / Robert Munro, CC BY 4.0) and from sentences of Tatoeba (tatoeba.org, CC BY 2.0 FR). Dictionary 2 was filtered with SCOWL: "Copyright 2000-2018 by Kevin Atkinson. Permission to use, copy, modify, distribute and sell these word lists, the associated scripts, the output created from the scripts, and its documentation for any purpose is hereby granted without fee, provided that the above copyright notice appears in all copies and that both that copyright notice and this permission notice appear in supporting documentation."

**Privacy.** The raw corpora are **not** in the repo (the disaster set has real people's requests). We commit only the word lists, the scripts and totals. A word may enter a list only if it appears in at least **3 different training messages**, is printable ASCII, has no run of 5 or more digits (phone numbers) and no `@`, `http` or `www`. Dictionary 2 adds: a word seen in fewer than **10** training messages must also be an ordinary English word in SCOWL (not a name list), so rare names, nicknames and typos stay out. The lists do contain common first names that the corpora use in many messages (for example "Tom", "Mary" and "Sami" from Tatoeba's example sentences), place names from the news, and "4636", the public Haiti emergency SMS number of the disaster set.

**Choosing dictionary 2** (validation sets, compact frame bytes ÷ typed bytes, lower is better): 43,755 candidate words and 334,019 candidate phrases; all scored by bytes saved per corpus byte (a word: its bytes + 1 space + 2 literal-header bytes − 2 token bytes per use; a capitalised form of a listed word: 2 per use; a phrase: 2 per use), one ranking, a phrase only after both its words.

| Variant | Disaster | Conversational | File | In the APK (zip) |
|---|---|---|---|---|
| **Ranked by bytes saved (chosen)** | **39.3 %** | **39.2 %** | 624 KB | 277 KB |
| + 5,000 common SCOWL words the corpora lack | 39.2 % | 39.6 % | 620 KB | 270 KB |
| + 15,000 common SCOWL words | 39.3 % | 39.8 % | 601 KB | 248 KB |

Adding dictionary words the corpora do not have did not help on held-out messages (phrases saved more), so dictionary 2 is purely the ranked list.

**Dictionary 1, steps.**
1. Load both corpora. Drop duplicates inside each set, and drop any validation or test message whose text (lower case, spaces collapsed) is also in a training set.
2. Cut the training messages into words exactly like the codec does, and count.
3. Score every word by expected bytes saved: `count × (bytes of the word + 1 space + 2 literal-header bytes − 2 token bytes)`, per corpus byte, both corpora weighted the same. The "+2" matters: without it, "I" and "a" score 0 and are left out, which costs 0.5 points (disaster) and 1.5 points (conversational) on validation.
4. Add the 13 punctuation marks and "…" first, the words of `sar.yaml`, then the best words, plus 10 % two-word phrases ("do you", "in the").
5. Choose the size and variant on the **validation** sets; report on the **test** sets only.

**Choosing the size** (validation sets; "words / UTF-8" = word-token packet bytes ÷ text bytes, lower is better):

| Entries | Phrases | Disaster | Conversational | File | In the APK (zip) |
|---|---|---|---|---|---|
| 2048 | 10 % | 78.0 % | 90.0 % | 15 KB | 7.6 KB |
| 4096 | 10 % | 65.6 % | 79.4 % | 32 KB | 15.5 KB |
| 8192 | none | 58.4 % | 76.7 % | 66 KB | 31.5 KB |
| **8192** | **10 %** | **55.8 %** | **71.2 %** | **67 KB** | **31.6 KB** |
| 8192 | 25 % | 56.5 % | 71.0 % | 68 KB | 31.9 KB |
| 16384 | 10 % | 49.7 % | 65.3 % | 135 KB | 65.5 KB |

We took 8192 entries with 10 % phrases: the upper end of the planned 2k-8k range. 16384 would save about 6 points more for 34 KB more in the APK, but it lets in many more rare words (more names); that can be dictionary 2. The `sar.yaml` words cost 0.5 points on these English sets (they help rescue texts and German, which these sets do not measure); we kept them.

## Results (held-out test sets)

`python -I spec/tools/words_dict2.py measure --disaster DIR --tatoeba eng_sentences.tsv --scowl SCOWL/final`. Same test sets as dictionary 1 (never used to build or choose). Whole payloads, a 2-byte message id in envelope forms, no GPS position, a chat that packs, compact on. Messages over 400 bytes are left out (2 conversational, 25 disaster). Every held-out message (validation and test) was checked: `decode(encode(text)) == text`. "Words" are pieces with a letter.

| | Conversational (Tatoeba test) | Disaster messages (test split) |
|---|---|---|
| Messages | 4998 | 2357 |
| Words in them | 38,323 | 51,652 |
| Typed text (UTF-8) | 199,681 B (5.21 B per word) | 317,307 B (6.14 B per word) |
| Words found in the dictionary: v1 / **v2** | 93.9 % / **98.5 %** | 89.6 % / **93.8 %** |
| All as word tokens v1 in the envelope (spec 2.1) | 140,547 B (3.67 B per word) | 176,569 B (3.42 B per word) |
| All as compact frames v2 (spec 2.2) | 78,174 B (**2.04 B per word**) | 124,044 B (**2.40 B per word**) |
| **Sent, spec 2.1 rule** (DEFLATE, words v1, normal) | 137,669 B, **31.1 % saved** | 176,659 B, **44.3 % saved** |
| **Sent, spec 2.2 rule** (strictly smallest) | 78,139 B, **60.9 % saved** | 123,914 B, **60.9 % saved** |
| Median saving per message: 2.1 / 2.2 | 28.6 % / 61.5 % | 43.6 % / 62.2 % |
| Sent compact (not normal text): 2.1 / 2.2 | 86.8 % / **99.7 %** | 98.4 % / **99.5 %** |
| 2.2 forms: compact frame / words in envelope / DEFLATE / normal | 99.6 % / 0 % / 0 % / 0.3 % | 98.9 % / 0 % / 0.6 % / 0.5 % |
| Fits one packet (200 B): typed / 2.1 / 2.2 | 99.5 % / 99.9 % / 100 % | 82.6 % / 99.5 % / 100 % |
| Word list in the APK (zip) | v1 31.6 KB, v2 about 277 KB | |

Why the jump: the envelope (id, tags, lengths, dictionary number) cost about 7 to 9 bytes per text, more than a short text saves, and dictionary 1 knew only 8,192 entries. The frame costs 2 bytes, and dictionary 2 knows almost every word in these sets.

With spec 2.1 and dictionary 1, 13.2 % of the conversational texts stayed normal text: for short sentences the envelope cost more than the words saved. With the compact frame that is 0.3 % (texts like "Ok" or "Hi!", where the normal text is as small or smaller).

**Our own team texts** (`spec/tools/text-corpus.json`, the 20 German and 20 English held-out lines used for DEFLATE, payloads with a 2-byte id): English 1,087 B typed → **437 B** sent, all 20 as compact frames (spec 2.1: 705 B). German 1,183 B → 839 B: 18 lines stay DEFLATE, 2 go compact. The word list is English; German gains little from compact text, and loses nothing (the smallest form wins).

**Honest limits.**
- Measured in software (the real codec and gateway code, an in-memory radio). Not yet verified over physical radios.
- Tatoeba sentences are short and tidy, and the test sentences come from the same collection as the training ones (split by hash), so 98.5 % found words is optimistic; real chat has more typos and slang. The disaster set (93.8 %) is the harder, more realistic case.
- The compact frame carries no GPS position. With the setting on, free texts from the app go without the automatic GPS fix (reports keep it); a text that does carry a position goes as word tokens in the envelope (about 11 bytes more than the frame).
- Dictionary 2 adds about 277 KB to the APK.
- The disaster messages are mostly English translations (many from Haitian Creole) and news sentences, not German volunteer radio talk.
- For context only (not compared directly): an earlier analysis on 2,926 train / 421 held-out Haiti requests found 5.21 UTF-8 bytes per word and 92.0 % held-out word coverage with 2,048 entries. Our 2048-entry run reaches 72 % on the disaster validation set (all genres, ours counts every non-punctuation piece including numbers).

## Reproduce

```bash
mkdir -p /tmp/corpus/disaster /tmp/corpus/tatoeba /tmp/corpus/scowl      # outside the repo
for f in disaster_response_training.csv disaster_response_validation.csv disaster_response_test.csv; do
  curl -sSL -o /tmp/corpus/disaster/$f https://raw.githubusercontent.com/rmunro/disaster_response_messages/main/$f; done
curl -sSL https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2 | bunzip2 > /tmp/corpus/tatoeba/eng_sentences.tsv
curl -sSL https://downloads.sourceforge.net/project/wordlist/SCOWL/2020.12.07/scowl-2020.12.07.tar.gz | tar xz -C /tmp/corpus/scowl
A="--disaster /tmp/corpus/disaster --tatoeba /tmp/corpus/tatoeba/eng_sentences.tsv"
S="--scowl /tmp/corpus/scowl/scowl-2020.12.07/final"
.venv/bin/python -I spec/tools/words_dict2.py select  $A $S          # dictionary 2: variants on validation
.venv/bin/python -I spec/tools/words_dict2.py build   $A $S --check  # rebuild and compare with the frozen file
.venv/bin/python -I spec/tools/words_dict2.py measure $A $S          # the test-set table above
.venv/bin/python -I spec/tools/words_dict.py  build   $A --check     # dictionary 1
```

Tatoeba changes daily, so `build --check` matches the frozen files only with the export of 25 Nov 2026 (2,041,577 lines). The frozen files and their SHA-256 are the reference, not the download.
