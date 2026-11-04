# ADR 0001 — Core in Rust, UI in Kotlin

**Stato:** accettato (2026-11-04)

## Contesto
Coffer deve decifrare e cifrare volumi VeraCrypt su Android senza root. Aprire un volume richiede di provare molte combinazioni di KDF e cifrario, con centinaia di migliaia di iterazioni ciascuna. Le chiavi devono essere cancellate dalla memoria in modo affidabile.

## Decisione
Il formato, la crittografia e i filesystem vivono in un workspace Rust (`core/`), compilato con l'NDK ed esposto a Kotlin tramite UniFFI. L'interfaccia è in Kotlin con Jetpack Compose.

## Conseguenze
- Prestazioni native, uso dell'AES hardware ARMv8, parallelismo con rayon; sicurezza della memoria; azzeramento delle chiavi con `zeroize`.
- La build è più complessa (cargo-ndk, tre ABI) e servono competenze in due linguaggi.
