# Compact text (word tokens)

An optional way to send ordinary chat texts in fewer bytes. Off by default.

- Every common English word becomes **2 bytes** (a number from a shared word list).
- A single space between two such words costs **nothing**.
- Everything else (names, numbers, coordinates, emoji, German, typos) is copied **exactly** as raw bytes.
- The receiver gets back the **exact text**, every character, space and line break. No word is ever "corrected".

The wire format is in `spec/SPEC.md` section 3.6. This page says how to use it, where the word list comes from, and what we measured.

## Turn it on

**App:** Settings (gear) → "Use compact encoding for text messages" (German: "Kompakte Kodierung für Textnachrichten verwenden"). Default: off.

**Gateway:** `--compact-text on` (or `RESCUECODEC_COMPACT_TEXT=on`). Default: off. `GET /v2/health` shows `"compact_text": true`.

It is used only where a chat already packs texts for RescueCodec: Auto with only RescueCodec users heard, or "Always compressed". Never on a public channel, never to someone with the stock Meshtastic app. In such a chat the sender builds three forms and sends the smallest:

| Form | What it is |
|---|---|
| Normal text | The words as a Meshtastic text, readable by every app |
| DEFLATE (packed) | The existing packed text (SPEC 3.5) |
| Word tokens (compact) | The new form |

A tie or a loss keeps the existing choice. Sizes are whole packets (id, position and dictionary number included).

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
| Sent as | word tokens, **66 bytes** on air (DEFLATE would be 70, normal text 136) |
| Recovered (`POST /v2/decode` with the sent hex) | exactly the typed text (`==` true) |

A second text with a name, a German place and coordinates, `Team 3 at Löwenburg hut. We found Aylin Demir, conscious, ankle hurt.\nMeet at 50.6900, -1.2500 in 20 min 🙂` (110 bytes): word tokens would be 104 bytes, DEFLATE 83, so the gateway sent DEFLATE (83 bytes) and the text came back exactly. That is the fallback working.

On a phone: two phones with the new APK, the same private channel (a chat that packs), the setting on in the sender. The composer's byte line shows the form, for example "Compact words · 66 B (as text 136 B)" (the phone adds its GPS position, so its numbers are about 11 bytes higher). Details of the sent message say "Compact words, dictionary 1".

## Older apps

An app or gateway from before this feature (spec 2.0) cannot read a compact text. It **never shows wrong words**: it rejects the packet. The old app shows "Unreadable message", the old gateway counts it as rejected. If some team members still have the old app: leave the setting off, or set that chat to "Always normal".

An app that has a newer word dictionary than this one shows "Message encoded with a dictionary this app does not have (word dictionary 2), please update the app". Never a guess.

## Long texts

- A chat that packs takes up to **400 bytes** of text. One radio message is still at most **200 bytes**.
- Other chats: 180 bytes, as before.
- If a text does not fit one radio message in any form, it is cut at the last whole character that fits, and "…" is added. The composer says "Too long: will be cut to fit one radio message". There is no multi-part message.

## Where the word list comes from

`spec/vocab/words-dict-v1.txt`, 8192 entries, 66,607 bytes, SHA-256 `ffe93afb4623fcd364479169281ca745cc263317a4f83ce27f77d8d37b26a0d8`. Built by `spec/tools/words_dict.py` (reproducible, no randomness).

| Source | What we used | Licence | OK to ship a word list? |
|---|---|---|---|
| [Disaster response messages](https://github.com/rmunro/disaster_response_messages) (Robert Munro, Figure Eight) | the English `message` column, its own train / validation / test split | CC BY 4.0 | Yes, with attribution (this page) |
| [Tatoeba](https://tatoeba.org/en/downloads) English sentences (`eng_sentences.tsv`) | all English sentences, split 90 / 5 / 5 by a hash of the text | CC BY 2.0 FR | Yes, with attribution (this page) |
| `spec/vocab/sar.yaml` (our own) | the English and German rescue words | Apache-2.0 | Yes |

Attribution: word list derived from "Disaster Response Messages" (Figure Eight / Robert Munro, CC BY 4.0) and from sentences of Tatoeba (tatoeba.org, CC BY 2.0 FR).

**Privacy.** The raw corpora are **not** in the repo (the disaster set has real people's requests). We commit only the word list, the script and totals. A word may enter the list only if it appears in at least **3 different training messages**, is printable ASCII, has no run of 5 or more digits (phone numbers) and no `@`, `http` or `www`. The list does contain common first names (for example "Tom" and "Mary", which Tatoeba uses in many example sentences) and place names from the news.

**Steps.**
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

`python -I spec/tools/words_dict.py measure --disaster DIR --tatoeba eng_sentences.tsv`. Whole packets with a 2-byte message id, no GPS position, a chat that packs, compact on. Messages over 400 bytes are left out (2 conversational, 25 disaster). Every held-out message (validation and test, both corpora) was checked: `decode(encode(text)) == text`.

| | Conversational (Tatoeba test) | Disaster messages (test split) |
|---|---|---|
| Messages | 4998 | 2357 |
| Original text (UTF-8) | 199,681 B | 317,307 B |
| All as normal text | 199,681 B | 317,307 B |
| All as DEFLATE packets | 186,952 B | 231,426 B |
| All as word-token packets | 140,547 B | 176,569 B |
| **Best of (what is sent)** | **137,669 B** | **176,659 B** |
| **Saved vs. the original text** | **31.1 %** | **44.3 %** |
| Median saving per message | 28.6 % | 43.6 % |
| Today's rule without compact (DEFLATE or normal) | 9.7 % saved | 25.4 % saved |
| Sent as word tokens / DEFLATE / normal text | 83.3 % / 3.4 % / 13.2 % | 92.1 % / 6.3 % / 1.6 % |
| Words found in the dictionary | 93.9 % | 89.3 % |
| Fits one packet (200 B) as normal text | 99.5 % | 82.6 % |
| Fits one packet, best form, uncut | 99.9 % | 99.5 % |
| Word list in the APK | 31.6 KB compressed (66.6 KB file) | |

"Sent as normal text" means compact did not help (mostly very short sentences, where the packet's own bytes cost more than they save).

**Our own team texts** (`spec/tools/text-corpus.json`, the 20 German and 20 English held-out lines used for DEFLATE): English best-of 705 B instead of 798 B with DEFLATE alone (16 of 20 go as words). German: word tokens never win, every German line stays DEFLATE (841 B). The word list is English; German gains nothing from compact text, and loses nothing.

**Honest limits.**
- Measured in software. Not yet verified over physical radios.
- Tatoeba sentences are short and tidy; real chat has more typos and slang.
- The disaster messages are mostly English translations (many from Haitian Creole) and news sentences, not German volunteer radio talk.
- For context only (not compared directly): an earlier analysis on 2,926 train / 421 held-out Haiti requests found 5.21 UTF-8 bytes per word and 92.0 % held-out word coverage with 2,048 entries. Our 2048-entry run reaches 72 % on the disaster validation set (all genres, ours counts every non-punctuation piece including numbers).

## Reproduce

```bash
mkdir -p /tmp/corpus/disaster /tmp/corpus/tatoeba      # outside the repo
for f in disaster_response_training.csv disaster_response_validation.csv disaster_response_test.csv; do
  curl -sSL -o /tmp/corpus/disaster/$f https://raw.githubusercontent.com/rmunro/disaster_response_messages/main/$f; done
curl -sSL https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2 | bunzip2 > /tmp/corpus/tatoeba/eng_sentences.tsv
.venv/bin/python -I spec/tools/words_dict.py select  --disaster /tmp/corpus/disaster --tatoeba /tmp/corpus/tatoeba/eng_sentences.tsv
.venv/bin/python -I spec/tools/words_dict.py build   --disaster /tmp/corpus/disaster --tatoeba /tmp/corpus/tatoeba/eng_sentences.tsv --check
.venv/bin/python -I spec/tools/words_dict.py measure --disaster /tmp/corpus/disaster --tatoeba /tmp/corpus/tatoeba/eng_sentences.tsv
```

Tatoeba changes daily, so `build --check` matches the frozen file only with the export of 25 Nov 2026 (2,041,577 lines). The frozen file and its SHA-256 are the reference, not the download.
