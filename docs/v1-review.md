# RescueCodec review (Tue 24 Nov, 22:30)

**Verdict: good idea, good start. Five things to fix before Thursday.**

## What it does today
A firefighter types: "I found the person. He is unconscious and needs an oxygen mask. We need at least two people."
That is 92 bytes. RescueCodec turns it into **12 bytes**, sends it, and the other side reads it back as a full sentence. The receiver can press **Accept**, and the sender sees who took the job.

## What is already good
- It works. 16 of 17 tests pass. The one failure is an old library version on Denis's Mac, not a bug.
- The **Accept** button is the best part. Today a sender doesn't know if anyone took the job. Show this on stage.
- The team is honest about what is real and what is simulated. Radio experts will respect that.

## 5 things to fix

**1. Range is the new top priority** (updated: our users are rescue teams in forests and mountains, not firefighters)
- GPS works outdoors, so the existing coordinates are fine. Floor and side are not needed.
- What matters now: how far a message gets. Small packets let us use the slowest, longest-range radio setting. Example: on that setting, the 92-byte text takes about 6.4 seconds on air and our 12 bytes about 2.2 seconds.
- Fix: test the range outside on Wednesday and write down the real numbers (distance, signal, how many messages arrived). Put one radio up high as a relay (a hill, a tree, a car roof, or a drone).

**2. It only knows one kind of message: "person found".**
- Example: "Evacuate Trupp 2 now" or a Mayday can't be sent.
- Fix: add **Mayday** at least, and let it jump ahead of other messages.

**3. Someone will ask: "Why shrink it? The normal text already fits."**
- True: 92 bytes already fits in one mesh message.
- Better answers:
  - It's faster on air. The team's own estimate: about 1 second for the text versus 0.4 seconds for the 12-byte version, so the channel holds **about 2.4x more messages**.
  - **Voice.** 4 seconds of speech is about 64,000 bytes. RescueCodec makes it 12 bytes. That is the real wow. **Speak on stage, don't type.**

**4. The German version is half English.**
- Example: "Benötigte Ausrüstung: oxygen mask", "Priorität: critical".
- Fix: translate the words (Sauerstoffmaske, Defibrillator, kritisch).

**5. It has never gone from one real radio to another.**
- So far it runs in a simulation on one laptop. One radio was only plugged in.
- Fix (most important): radio A sends, radio B shows the message, B presses Accept, and A turns green. Write down the bytes and the time it took.

## Plan for Wednesday
1. Two real radios talking (ask Dräger for a second one if needed). Use our own private channel so we don't disturb Dräger's demo.
2. Outdoor range test with real numbers, plus one relay placed high.
3. Add search-and-rescue messages: Mayday, sector searched, need evacuation/helicopter.
4. Make the German version fully German.
5. Then: speak instead of type (offline speech-to-text).
6. Then: if Wi-Fi/5G is there, send the full message; if not, send the 12 bytes. This fits Challenge 3 exactly and gets FBI GmbH on board.

Skip the small image idea for now. 32x32 pixels is hard to see on a projector.

## Don't forget
**Team registration by Wed 10:00**: team name and full names, by email to the organiser. Without it there is no pitch slot.
