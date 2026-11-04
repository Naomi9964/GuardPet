# GuardPet

## What it is

GuardPet is an Android security-guardian pet: a floating cat that watches
over the phone, automatically detects scams and risky apps, and warns the
user in plain language. It is built for people who do not read security
jargon — the warning tells you what happened, why it is risky, and what to
do next.

Track: **Build for Your World** — Meta Global AI Developer Hackathon 2026,
indie (solo) entry. Building period: October 27 – November 4, 2026.

## What it does

- Floating pet cat: walking, idle, sleeping, alerting, tap-to-chat,
  Chinese/English switching, pin / walk toggle
- App permission scan (logistic-regression model)
- Scam SMS detection
- Suspicious call screening
- Suspicious link check (offline lexical scoring)
- Open Wi-Fi warning (fully offline)
- AI explanations (opt-in, OFF by default, with a privacy notice in the
  app): tap the cat on an alert to get a plain-language explanation. The
  bubble switches to "thinking…" immediately (up to 2 minutes), shows the
  explanation for 15 seconds, calls the API only once per alert, and never
  lets an old explanation overwrite a newer alert
- Link explanations: when a shared link is judged dangerous, an "AI
  explanation" button lists only the rules that actually scored
- Companion chat: tap the cat to chat, last 6 turns kept, voice input
  included
- API key storage encrypted with Android Keystore (AES/GCM); an old
  plaintext key is migrated automatically on first open, and a decryption
  failure is shown honestly instead of pretending the key is active

## How it works

**Detection layer (on-device, deterministic).** Two on-device models plus a
rule engine decide every verdict. Nothing about detection leaves the phone,
and no detection decision needs the cloud.

**Explanation layer (Meta Model API, opt-in).** Only after a verdict is
made, and only if the user opted in and provided a key, the app asks a Meta
model to explain it. The model can never overturn a verdict. With no key, no
network, or an API error, the app silently falls back to scripted text.

## Meta products used

| Product | Model / endpoint | Used for |
|---|---|---|
| Meta Model API — chat completions | `muse-spark-1.3` via `api.meta.ai/v1/chat/completions` | Alert explanations, link explanations, companion chat |
| Meta Model API — speech transcription | `muse-voice-transcribe-1.0` via `api.meta.ai/v1/asr/transcribe` (file-based multipart upload, not a realtime stream) | Voice input in chat; falls back to system speech recognition |

Privacy notes: voice recordings are sent to Meta for transcription and are
not stored (the temporary file is deleted after use). Alert explanations send
the alert's trigger details to the Model API — that is why the layer is
opt-in and off by default.

## Measured results (with limits stated)

- Scam SMS model: 67,008 messages, Precision 0.962 / Recall 0.975 /
  F1 0.969. Limit: mostly English; Chinese scam SMS is unverified.
- App permission model: Drebin-215 (15,036 apps), Precision 0.895 /
  Recall 0.922 / F1 0.908. Limit: Drebin data is from 2010–2012, so treat
  this as demo-grade evidence and cite the date when quoting it.
- LLM tuning (VM, not a real phone): 8 prompt groups, all finished
  cleanly, zero hallucinations in that set, about 330 output tokens per
  explanation.

## Testing status (verified 2026-11-03, on camera)

- Emulator (Pixel 10a): build, install, and full checklist pass. Filmed
  verification: alert explanation (thinking state, then explanation),
  link explanation (dangerous paypa1-style link and normal Wikipedia link),
  call blocking with a test number, and the opening opt-in flow.
- Real device (Xiaomi 9, Android 11), filmed 2026-11-03:
  - Mic button (v1.1.9 fix): the permission dialog appears and grants
    correctly; voice input works.
  - Chat: a typed question and its reply work; a voice round works
    end to end (record, Muse Voice transcription, reply).
  - Latency in the filmed session: chat replies arrived within about
    10 seconds; voice transcripts arrived a few seconds after stopping.
  - Plain-language check: replies are plain-language and correct
    (safe-account transfer warning, prize-fee warning).
  - Transcription had minor ASR errors with meaning intact ("staying"
    for "saying", "price" for "prize"); the replies still addressed the
    right scam. A second filmed voice round shows the transcript filling
    the input box without auto-sending.
  - Call screening is NOT supported on this phone: the system
    call-screening role does not exist on Xiaomi 9, so the app correctly
    reports it as unsupported. This is a device limit, not an app bug;
    call blocking itself was verified on the emulator only.
  - Keystore note: key entry and AI features work on the real phone;
    the encrypted-at-rest (`v1:` ciphertext) format was not directly
    inspected on this phone (no adb access during filming).

## Build and run

`./tools/build_apk.sh` builds `app-debug.apk` with aapt2 + kotlinc + d8
(no Gradle wrapper in this repo). It needs JDK 17, an Android SDK with
build-tools 34.0.0 and platform android-34, and kotlinc, at
`$HOME/workspace/jdk`, `$HOME/workspace/android-sdk`, and
`$HOME/workspace/kotlinc` (or edit the paths at the top of the script).
Install on a device or emulator. Enter your Meta Model API key in the
app's settings and turn on the AI explanation toggle; without a key the
app runs exactly as before, with scripted text only.

## License

MIT — see the LICENSE file.
