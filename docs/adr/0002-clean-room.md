# ADR 0002 — Reimplementazione clean-room del formato VeraCrypt

**Stato:** accettato (2026-11-04)

## Contesto
Il codice di VeraCrypt è soggetto ad Apache 2.0 e, per le parti ereditate, alla TrueCrypt License 3.0, che la FSF non considera libera e che F-Droid probabilmente non accetta. Il nome VeraCrypt è un marchio di IDRIX.

## Decisione
Il formato viene implementato soltanto a partire dalla documentazione pubblica, dagli standard e dai container usati come scatola nera, secondo `legal/CLEAN_ROOM.md`. Non si copia, non si traduce e non si consulta il codice sorgente di VeraCrypt o TrueCrypt.

## Conseguenze
- Licenza del codice interamente nostra; distribuzione possibile su Play Store e F-Droid.
- Sviluppo più lento. I dettagli non documentati si risolvono con i test sul corpus di container reali.
