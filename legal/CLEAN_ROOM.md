# Procedura clean-room

Coffer reimplementa il formato dei volumi VeraCrypt **senza usare il codice sorgente** di VeraCrypt o di TrueCrypt. Il codice VeraCrypt è distribuito con Apache 2.0 e, per le parti ereditate, con la TrueCrypt License 3.0, che non è una licenza libera riconosciuta. Copiarlo, tradurlo o parafrasarlo renderebbe Coffer un'opera derivata soggetta a quella licenza.

## Regole

1. Chi lavora al core (`core/`) **non apre** il codice sorgente di VeraCrypt né di TrueCrypt (repository, file `.c` / `.cpp` / `.h`, diff, snippet nei forum o nelle issue).
2. Fonti ammesse:
   - la documentazione pubblica di VeraCrypt (veracrypt.io, cartella `docs` dell'installazione), in particolare la specifica del formato del volume, le pagine sugli algoritmi, sulla derivazione delle chiavi, sul PIM e sui keyfile;
   - standard e paper degli algoritmi (IEEE 1619, FIPS 197, RFC 8018, RFC 9106, GOST R 34.11-2012, ecc.);
   - vettori di test pubblicati;
   - il comportamento di VeraCrypt osservato come **scatola nera**, cioè i container creati con il programma ufficiale e usati come oracolo nei test.
3. Ogni fonte consultata va annotata nel log qui sotto: data, URL o percorso, informazione presa.
4. Le pull request che toccano `core/` dichiarano: "Confermo di non aver consultato codice VeraCrypt/TrueCrypt".
5. Le dipendenze di terze parti devono avere licenza compatibile con GPLv3 (MIT, Apache-2.0, BSD, ISC, Zlib, Unicode-3.0). La verifica è automatica con `cargo deny`.

## Log fonti

| Data | Fonte | Informazione presa |
|---|---|---|
| 2026-11-04 | https://veracrypt.io/en/VeraCrypt%20License.html | Termini di licenza e restrizioni sul nome |
| 2026-11-04 | https://veracrypt.io/en/Release%20Notes.html | Argon2id introdotto in 1.26.29 |
| 2026-11-04 | https://veracrypt.io/en/Command%20Line%20Usage%20for%20Windows.html | Sintassi di `VeraCrypt Format.exe /create`, nomi di cifrari e hash per il corpus |
| 2026-11-04 | Comportamento osservato di VeraCrypt Format 1.26.29 | `argon2id` non accettato (`argon2` sì); PIM < 485 richiede password ≥ 20 caratteri; exFAT con Argon2 richiede il driver aggiornato |
