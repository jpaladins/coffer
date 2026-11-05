# P3: test end-to-end sull'emulatore (2026-11-05)

Ambiente: emulatore Android 17 (API 37) x86_64, 4 core. Build di debug con `--ez e2e_screens true` (solo debug: disattiva FLAG_SECURE per gli screenshot) e build release con R8. Container reali creati con VeraCrypt 1.26.29, copiati in `/sdcard/Download`. Interazione tramite `uiautomator dump` + `input tap`, sul selettore di file di sistema reale.

| # | Passo | Esito | Screenshot |
|---|---|---|---|
| 1 | Primo avvio: stato vuoto | OK | `p3-screens/01-empty.png` |
| 2 | "Aggiungi container" → selettore di sistema → Download → `vacanze.hc` | OK, permesso SAF persistente | `02-list.png` |
| 3 | Password sbagliata | Messaggio "Wrong password, PIM or keyfiles…", campo svuotato | `05-wrong.png` |
| 4 | Tastiera sul campo password | **Bug trovato e corretto**: la tastiera suggeriva la password. Ora tastiera di tipo password, senza autocorrezione né suggerimenti | `04-wrong.png` (prima), `03-password.png` (dopo) |
| 5 | Password corretta (`coffer-test`, rilevamento automatico della KDF) | Elenco visibile dopo **3,4 s** dal tasto Invio | `06-browser.png` |
| 6 | Sottocartelle e breadcrumb | OK | `07-subfolder.png` |
| 7 | Visualizzatore di testo (`deep.txt`, byte casuali) | OK, nessun crash | `08-viewer.png` |
| 8 | Impostazioni del blocco automatico + nota sul marchio IDRIX | OK | `10-settings.png` |
| 9 | Blocco "Subito" → Home → riapertura | Ritorno all'elenco, container bloccato | `11-after-autolock.png` |
| 10 | Volume **nascosto** reale (`hidden-outer.hc`, `hidden-pass`) | Sbloccato (vuoto, come creato) | `12-hidden.png` |
| 11 | Build normale: FLAG_SECURE | Screenshot interamente nero (0% pixel non scuri) | `13-flag-secure.png` |
| 12 | APK release (R8, 5,8 MB) | Sblocco ed elenco OK, 0 crash, `dumpsys package`: nessun permesso | n/d (FLAG_SECURE) |

## Problemi aperti emersi
- **Accessibilità e password:** il dump di `uiautomator` mostra in chiaro il testo del campo password. Va verificato se i servizi di accessibilità ricevono il testo non mascherato e, se sì, va mitigato.
- **Accessibilità del FAB:** il pulsante flottante "Add container" non compare nel dump di accessibilità. Va verificato con TalkBack.
- Il browser non indica se si è aperto il volume esterno o quello nascosto (informazione disponibile in `VolumeInfo.hidden`).
- Non testato: "Apri con…" verso un'app esterna (sull'emulatore non c'è un'app per i file del corpus), esportazione via SAF sul dispositivo (la logica è coperta dai test unitari), telefono ARM reale.
