# Corpus di compatibilità VeraCrypt

Container creati con il programma ufficiale VeraCrypt e usati come **oracolo a scatola nera** nei test di Coffer (vedi `legal/CLEAN_ROOM.md`). L'output (`out/`) non è nel repository.

## Generazione

```powershell
powershell -ExecutionPolicy Bypass -File corpus\generate.ps1            # tutto
powershell -ExecutionPolicy Bypass -File corpus\generate.ps1 -Only pim  # solo le voci che corrispondono alla regex
```

Contenuto: 15 cifrari/cascate × SHA-512, AES × (SHA-256, Whirlpool, BLAKE2s-256, Streebog, Argon2id), una cascata con Argon2id, PIM 1 e 485, due keyfile, keyfile senza password, FAT32 da 64 MiB, exFAT, password UTF-8 (`pàsswörd€`). L'elenco completo con le credenziali è in `out/manifest.json`.

Note sulla versione verificata (1.26.29):
- `VeraCrypt Format.exe` accetta `/hash argon2` (oppure `blake2b-512`), ma **non** `argon2id`, che compare nella documentazione online della 1.26.30.
- Un PIM minore di 485 richiede una password di almeno 20 caratteri.
- Il formato exFAT richiede il driver VeraCrypt aggiornato: dopo un aggiornamento di VeraCrypt va riavviato il PC prima di creare volumi exFAT con Argon2id (verificato: dopo il riavvio `aes-argon2-exfat.hc` si crea correttamente).

## Test

```powershell
$env:COFFER_CORPUS = "$PWD\corpus\out"
cd core; cargo test -p coffer-volume --release --test corpus -- --ignored --nocapture
```

## Volumi nascosti (passaggio manuale)

La riga di comando di VeraCrypt per Windows non crea volumi nascosti. Si creano una volta dalla GUI:

1. VeraCrypt → **Create Volume** → *Create an encrypted file container* → *Hidden VeraCrypt volume* → *Normal mode*.
2. File: `corpus\out\hidden-outer.hc`, dimensione 20 MB. Volume esterno: AES, SHA-512, password `outer-pass`, filesystem FAT.
3. Volume nascosto: AES, SHA-512, dimensione 5 MB, password `hidden-pass`, filesystem FAT.
4. Aggiungere a `out\manifest.json` le due voci seguenti:

```json
{ "file": "hidden-outer.hc", "password": "outer-pass",  "pim": 0, "keyfiles": [], "cipher": "AES", "prf": "SHA-512", "fs": "FAT", "size": 20971520, "kind": "standard" },
{ "file": "hidden-outer.hc", "password": "hidden-pass", "pim": 0, "keyfiles": [], "cipher": "AES", "prf": "SHA-512", "fs": "FAT", "size": 20971520, "kind": "hidden" }
```

VeraCrypt richiede password di almeno 20 caratteri solo con PIM < 485, quindi queste password vanno bene. Se la GUI chiede conferma per password brevi, accettare.
