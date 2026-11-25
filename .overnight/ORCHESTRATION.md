# Overnight orchestration (for the Claude main session and its 30-minute check)

Script: /Users/denishoti/.claude/projects/-Users-denishoti-Denis-Hackathons-PMRExpo-2026/42dfbc78-ba86-44c4-939c-bb5a68f17c2e/workflows/scripts/rescuecodec-overnight-wf_0f827823-a59.js
State: .overnight/STATE.json (stage status, runId, attempts, transcriptDir). Markers: .overnight/stages/<stage>.done (written by the stage's commit step).

## Stage order
1. setup                      args {stage: "setup"}
2. spec                       args {stage: "spec"}                     (started early at 01:36; it only needed the sim venv + protoc)
3. build chains, in parallel
   - build-android            args {stage: "build", chain: "android"}     needs spec AND setup (Android toolchain)
   - build-python             args {stage: "build", chain: "python"}      needs spec
   - build-ts                 args {stage: "build", chain: "ts"}          needs spec
   - build-dashboard          args {stage: "build", chain: "dashboard"}   needs spec
4. integrate                  args {stage: "integrate"}                needs all 4 build chains done or blocked
5. design                     args {stage: "design"}                   needs integrate
6. final                      args {stage: "final"}                    needs integrate (changed 06:26: runs IN PARALLEL with design to be ready for 08:30)
7. handover                   args {stage: "handover"}                 needs design AND final

## On every check (cron every 30 min) and on every workflow notification
1. Read STATE.json and list .overnight/stages/.
2. A stage is DONE if its marker exists. Set status "done" in STATE.json.
3. A stage with status "running":
   - If its workflow notification said completed, but there is no marker: treat as failed.
   - If it failed or was stopped (notification, or its transcriptDir journal.jsonl unchanged for over 60 minutes and no live agents): resume it with
     Workflow({scriptPath, args: <same args>, resumeFromRunId: <its runId>}). Record the new runId and transcriptDir, attempts + 1.
   - Rate-limit or usage-limit errors do not count as attempts. Just resume again on the next check.
   - After 3 real failures: status "blocked", note the reason in STATE.md, and continue with the next stages (they handle missing parts).
4. Launch every stage whose dependencies are done or blocked and which is still "pending". Record its runId, transcriptDir, status "running", and the start time.
5. When handover is done: send a PushNotification ("RescueCodec overnight build finished. Read .overnight/MORNING_BRIEF.md"), delete the cron job, and stop.
6. If it is after 08:15 and handover is not done: write a short interim .overnight/MORNING_BRIEF.md from STATE.md and E2E_REPORT.md (if any), and send a PushNotification with the status. Keep going.

## Never
- Never push, never change remotes, never touch real radios or Bluetooth devices.
- Never edit hooks, settings or permissions.
- Do not start a second run of a stage that is already running.
