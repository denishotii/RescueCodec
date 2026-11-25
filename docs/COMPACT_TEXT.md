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

The wire formats are in `spec/SPEC.md`: section 3.6 (word tokens) and 3.7 (compact frame). This page says how to use it, where the word list comes from, and what we measured.

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
