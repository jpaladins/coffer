# ADR 0004 — Nessun permesso INTERNET, nessuna pubblicità

**Stato:** accettato (2026-11-04)

## Contesto
In un'app di crittografia la fiducia è il prodotto. Gli SDK pubblicitari richiedono il permesso di rete, includono codice chiuso e tracker, e la presenza di pubblicità ci escluderebbe da F-Droid.

## Decisione
L'app non dichiara il permesso `android.permission.INTERNET` e non contiene SDK di terze parti per pubblicità o analytics. Il progetto si sostiene con donazioni ed eventualmente con un'app "Supporter" a pagamento dopo la v1.0.

## Conseguenze
- Promessa di privacy verificabile da chiunque; un test in CI controlla il manifest unito.
- Niente crash reporting remoto: i report sono esportati manualmente dall'utente.
