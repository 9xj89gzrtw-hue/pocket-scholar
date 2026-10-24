# Third-party Notice

## Wikipedia article text

`app/src/main/assets/knowledge/pack.jsonl` contains the introductory extracts of 6,418
English Wikipedia articles, chosen from
[Wikipedia:Vital articles](https://en.wikipedia.org/wiki/Wikipedia:Vital_articles)
(Level 3 and Level 4).

* Source: `https://en.wikipedia.org/w/api.php` (`action=query&prop=extracts&exintro&explaintext`)
* License: [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
* Attribution: text © Wikipedia contributors, licensed under CC BY-SA 4.0. Each record
  keeps the article title, so every passage the app quotes is traceable to
  `https://en.wikipedia.org/wiki/<title>`.
* Modification: the extract is truncated to 1,400 characters and stored as JSONL; no other
  edits are made. The generated pack is therefore a derivative and is distributed under the
  same CC BY-SA 4.0 terms.

Rebuild it from scratch with:

```bash
python3 scripts/build_knowledge_pack.py   # first pass, Vital Articles L3 + L4
python3 scripts/fill_knowledge_pack.py app/src/main/assets/knowledge/pack.jsonl
```

## Model

The app ships **no** model weights. The demo video uses
[Qwen2.5-1.5B-Instruct-GGUF](https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF)
(Q4_K_M), which is Apache-2.0. The user supplies the `.gguf` file; the app never downloads
anything.

## llama.cpp

[ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) v0.7.0, MIT. The CI workflow
cross-compiles `llama-cli` with the Android NDK and copies it to `jniLibs/<abi>/libllama-cli.so`.
No llama.cpp source is vendored in this repository.

## Android

The app uses only the Android framework (`android.*`), no AndroidX, no Play Services.
