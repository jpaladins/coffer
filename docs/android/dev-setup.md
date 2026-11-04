# Ambiente di sviluppo (Windows)

| Strumento | Versione verificata | Note |
|---|---|---|
| Rust (rustup) | stable 1.99.1, host `x86_64-pc-windows-gnu` | target `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android` |
| WinLibs MinGW-w64 (UCRT) | winget `BrechtSanders.WinLibs.POSIX.UCRT` | fornisce `dlltool`/`gcc`, necessari alla toolchain GNU |
| cargo-ndk, cargo-deny | `cargo install --locked` | |
| Android Studio + SDK | API 35–37, NDK 28.2 / 30.0 | `%LOCALAPPDATA%\Android\Sdk` |
| JDK | 21 (Microsoft) | |
| VeraCrypt | 1.26.29 | serve per generare il corpus di test; il driver richiede un riavvio dopo l'installazione |

Prima di lavorare, in PowerShell:

```powershell
. .\scripts\env.ps1    # imposta PATH (cargo, MinGW) e ANDROID_NDK_HOME
.\scripts\check.ps1    # fmt, clippy, test, licenze, build Android
```

Per i test sul corpus reale vedi [corpus/README.md](../../corpus/README.md).
