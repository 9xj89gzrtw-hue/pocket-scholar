# Pocket Scholar v0.1.0

An offline research assistant for Android. No network permission, no Play Services,
no server: a local llama.cpp model plus a 6,418-article retrieval index that ships
inside the APK.

## What is in this release

| asset | what it is |
|---|---|
| `app-debug.apk` | the app, `arm64-v8a` and `x86_64`, minSdk 26 |
| `demo1.mp4`, `demo2.mp4` | screen recordings from a fresh emulator: install, model push, two questions |
| `01-home.png` … `03-answer-rome.png` | stills from the same run |
| `permissions.txt` | output of the check that `dumpsys package` lists no INTERNET permission |

## Install

```bash
adb install -r app-debug.apk

# any Qwen2.5 GGUF works; this is the one in the recording
curl -L -o model.gguf \
  https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf
adb push model.gguf /sdcard/Android/data/org.pocketscholar/files/model.gguf

adb shell am start -n org.pocketscholar/.MainActivity
```

Or copy the `.gguf` anywhere on the phone and use the **Model…** button.

## Verify the offline claim yourself

```bash
adb shell dumpsys package org.pocketscholar | grep -i internet   # prints nothing
```

The manifest has no `android.permission.INTERNET`, so the process cannot open a
socket even if a dependency tried to.

## How it works

```
question → BM25 over 6,418 bundled articles → top-4 passages (900 chars each)
        → ChatML prompt with citation rules → llama.cpp (bundled binary, CPU)
        → answer with [1] [2] citations + the source list
```

* llama.cpp `v0.7.0`, cross-compiled with the NDK, shipped as `libllama-cli.so`
  and executed as a subprocess — no JNI layer to keep in sync with the C API.
* Knowledge pack: Wikipedia Vital Articles levels 3–4, intro extracts, JSONL,
  indexed in memory at launch (inverted list, BM25 k1=1.2 b=0.75).
* Model: Qwen2.5-1.5B-Instruct Q4_K_M in the recording; 0.5B–3B all fit.
