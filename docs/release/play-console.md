# Google Play Console: guida per Coffer 1.0.0

Risposte già preparate per i moduli della Play Console. Le regole di Google cambiano spesso: **verifica ogni punto sulla Console** prima di inviare. Dove una regola non è certa, è indicato.

## 1. Account sviluppatore (solo tu)

- Sito: play.google.com/console. Serve un account Google.
- Quota una tantum di 25 USD e verifica dell'identità con un documento. La verifica può richiedere **alcuni giorni**: conviene avviarla subito.
- Tipo di account: **personale** (più semplice). Un account "organizzazione" richiede un numero D-U-N-S.
- **Account personali nuovi** (regola introdotta da Google a fine 2023, verificane la versione attuale): prima di pubblicare in produzione serve un **test chiuso con almeno 12 tester** iscritti per **14 giorni consecutivi**. Prepara una lista di indirizzi Gmail di amici o colleghi.

## 2. Chiave di firma (solo tu, una volta)

1. Da PowerShell, nella cartella del progetto: `powershell -ExecutionPolicy Bypass -File scripts\new-keystore.ps1`
2. Il comando crea `coffer-release.jks` nella tua cartella utente e `android/keystore.properties` (escluso da git).
3. **Copia il file .jks e la password su una chiavetta o in un gestore di password.** Con Play App Signing (obbligatorio per le app nuove) questa diventa la tua *chiave di caricamento*: se la perdi puoi chiederne la sostituzione a Google, ma è una procedura lenta.

Poi il pacchetto da caricare (AAB) lo preparo io: `gradlew :app:bundleRelease`.

## 3. Crea l'app

| Campo | Valore |
|---|---|
| Nome app | Coffer |
| Lingua predefinita | Italiano (it-IT); aggiungi Inglese (en-US) |
| App o gioco | App |
| Gratuita o a pagamento | Gratuita (non si può più rendere a pagamento in seguito) |
| ID pacchetto | `com.coffer.app` (fissato dal primo caricamento, non modificabile) |

## 4. Scheda dello store

- Titolo, descrizione breve e completa: già pronti in `fastlane/metadata/android/it-IT/` e `en-US/`.
- Categoria: **Strumenti**.
- Email di contatto: obbligatoria e pubblica. Valuta un indirizzo dedicato al progetto.
- **Da preparare (lo faccio io in una prossima sessione):**
  - icona 512×512 PNG;
  - grafica in evidenza 1024×500;
  - almeno 2 screenshot del telefono (formato 9:16).
- **Informativa sulla privacy:** pubblicata su **https://jpaladins.github.io/coffer-privacy/** (repository pubblico `jpaladins/coffer-privacy`, solo la pagina; sorgente in `docs/site/privacy/index.html`: dopo ogni modifica va copiata lì). In origine serviva un **URL pubblico**. Il testo è pronto in `docs/privacy.md`, ma va pubblicato su una pagina pubblica, per esempio GitHub Pages quando il repository diventerà pubblico, o un altro sito tuo.

## 5. Contenuti dell'app (sezione "Norme" / "Contenuti dell'app")

| Modulo | Risposta |
|---|---|
| Norme sulla privacy | https://jpaladins.github.io/coffer-privacy/ |
| Annunci | **No**, l'app non contiene annunci |
| Accesso all'app | Tutte le funzionalità sono disponibili senza credenziali né account |
| Classificazione dei contenuti (questionario IARC) | Categoria "Utilità / produttività"; nessuna violenza, contenuto sessuale, linguaggio, sostanze, gioco d'azzardo; nessuna interazione tra utenti; nessuna condivisione della posizione; nessun acquisto digitale |
| Pubblico di destinazione | **13-15, 16-17, 18+** (non sotto i 13 anni: evita le norme Famiglie; solo 18+ può limitare la disponibilità). "Può attirare i bambini?": No |
| App di notizie | No |
| App governativa | No |
| Funzionalità finanziarie | Nessuna |
| Salute | Nessuna |

### Sicurezza dei dati (Data safety)

| Domanda | Risposta |
|---|---|
| L'app raccoglie o condivide dati utente? | **No** |
| Tutti i dati sono criptati in transito? | Non applicabile (nessun dato trasmesso: l'app non ha il permesso Internet) |
| Gli utenti possono chiedere la cancellazione dei dati? | Non applicabile (nessun dato raccolto) |

Nota da poter aggiungere nella descrizione: i file restano sul dispositivo, cifrati; password e chiavi non lasciano mai il telefono.

### Servizi in primo piano (foreground service)

Coffer dichiara `FOREGROUND_SERVICE_DATA_SYNC`. Google chiede una **dichiarazione** con:

- **Descrizione:** "Importazione di file scelti dall'utente in un container cifrato. Il servizio resta attivo solo durante la copia e mostra l'avanzamento; si ferma al termine."
- **Impatto se interrotto:** la copia si interrompe e il file parziale viene rimosso; l'utente deve ripeterla.
- **Video dimostrativo:** un breve video dell'importazione. Lo registro io dall'emulatore e te lo passo.

### Crittografia ed esportazione

La Console può chiedere della conformità alle norme statunitensi sull'esportazione. Coffer usa crittografia standard ed è software open source pubblicamente disponibile; vedi `legal/EXPORT.md`. Ricorda la **notifica TSU** via e-mail quando il codice diventa pubblico.

### Marchi

Nella scheda usa "VeraCrypt" solo in forma descrittiva, con la frase "Coffer non è affiliato a IDRIX. VeraCrypt® è un marchio di IDRIX." È già presente nei testi preparati.

## 6. Test e rilascio

1. **Test interno** (fino a 100 tester, disponibile in pochi minuti): carica il primo AAB qui per provarlo sul tuo telefono.
2. **Test chiuso** con 12 o più tester per 14 giorni, se il tuo account lo richiede.
3. **Produzione**: richiedi l'accesso dopo il test chiuso; la prima revisione può richiedere alcuni giorni.

## Cosa resta da fare a me

- Asset grafici: icona 512, grafica 1024×500, screenshot IT/EN.
- AAB firmato con la tua chiave di caricamento (dopo il punto 2).
- Video del servizio in primo piano.
- Pagina dell'informativa pronta da pubblicare.
