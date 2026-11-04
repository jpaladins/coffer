# Coffer

App Android open source per aprire, modificare e creare container compatibili con VeraCrypt®, senza root e senza accesso a internet.

> Coffer non è affiliato né approvato da IDRIX. VeraCrypt® è un marchio di IDRIX.

## Struttura

| Cartella | Contenuto |
|---|---|
| `core/` | Workspace Rust: motore crittografico e dei volumi |
| `android/` | App Android (Kotlin + Jetpack Compose) — dal piano P3 |
| `corpus/` | Generatore di container di test con VeraCrypt |
| `docs/` | Specifiche, piani, ADR, brand, sicurezza, QA |
| `legal/` | Licenza GPLv3, NOTICE, marchi, procedura clean-room |
| `scripts/` | Script di sviluppo (`env.ps1`, `check.ps1`) |

## Sviluppo

Requisiti e installazione: [docs/android/dev-setup.md](docs/android/dev-setup.md).

```powershell
. .\scripts\env.ps1
.\scripts\check.ps1
```

## Licenza

GPL-3.0-or-later — vedi [legal/LICENSE](legal/LICENSE). Il codice è scritto con procedura clean-room ([legal/CLEAN_ROOM.md](legal/CLEAN_ROOM.md)): non contiene codice di VeraCrypt o TrueCrypt.
