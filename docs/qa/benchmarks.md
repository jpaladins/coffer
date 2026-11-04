# Benchmark di apertura dei volumi

Strumento: `core/coffer-volume/examples/bench_open.rs` (build `--release`). Container dal corpus (`corpus/`), PIM predefinito.

## 2026-11-05 — P1

| Scenario | PC (Windows, 16 thread) | Emulatore Android 17 x86_64 (4 core) |
|---|---|---|
| AES / SHA-512, KDF indicata | 352 ms | 490–885 ms |
| AES / SHA-512, rilevamento automatico | 501 ms | 665–713 ms |
| Serpent-Twofish-AES / SHA-512, automatico | ~360 ms | 708 ms |
| exFAT 64 MiB, AES / SHA-512, automatico | ~435 ms | 661 ms |
| AES / Argon2id (416 MiB), automatico | ~5,2 s | 16,4 s |
| Password sbagliata (tutte le KDF, 4 header) | ~30 s | 56,5 s |

### Note
- I target della spec (meno di 3 s con KDF indicata, meno di 15 s con il rilevamento automatico) sono **rispettati sull'emulatore** per i volumi PBKDF2. La validazione su un **dispositivo ARM reale** spetta a P3.
- Prima della correzione dell'ordine di ricerca, il rilevamento automatico sull'emulatore variava tra 1,7 e 8,4 s, perché SHA-512 finiva in coda dietro le altre KDF. Ora SHA-512, il default di VeraCrypt, viene provato da solo per primo.
- **Password sbagliata: 56 s sull'emulatore.** Il costo dipende dal formato: 4 header × 5 PBKDF2 × 500 000 iterazioni, più Argon2id. Raccomandazioni per P3:
  1. nella UI, la scelta facoltativa della KDF, ricordata per ogni container dopo la prima apertura riuscita;
  2. al primo tentativo `try_backup_headers = false` (dimezza il tempo), con "Prova header di backup" come azione esplicita;
  3. una barra di avanzamento con il pulsante Annulla, che risponde entro 1 s.
- Argon2id con PIM 0 alloca 416 MiB: su dispositivi con poca RAM `max_kdf_memory` va impostato in base a `ActivityManager.MemoryInfo`.
