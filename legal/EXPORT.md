# Export control (crittografia)

Coffer implementa crittografia forte (AES, Serpent, Twofish, Camellia, Kuznyechik in XTS; PBKDF2 e Argon2id) per proteggere i file dell'utente. Questo documento registra l'analisi richiesta dalla spec (§9) prima del primo rilascio. **Non è una consulenza legale**: chi pubblica l'app ne resta responsabile.

## Unione europea: Reg. (UE) 2021/821 (duplice uso)

- La crittografia di Coffer rientrerebbe nella categoria 5, parte 2 (sicurezza delle informazioni).
- La **Nota generale sul software** (Allegato I) esclude dai controlli il software "di pubblico dominio" e quello "generalmente disponibile al pubblico" venduto al dettaglio senza restrizioni. Coffer è pubblicato con codice sorgente aperto (GPLv3) e distribuito gratuitamente senza restrizioni: rientra nell'esclusione.
- Conseguenza: nessuna autorizzazione all'esportazione necessaria per la distribuzione tramite Play Store, F-Droid e GitHub.

## Stati Uniti: EAR

- Il codice crittografico open source pubblicamente disponibile è classificato 5D002 ma può essere esportato con l'eccezione **TSU** (EAR §740.13(e)) **dopo una notifica** via e-mail a BIS e alla NSA con l'URL del codice sorgente (EAR §742.15(b)).
- La notifica serve solo quando il codice viene messo a disposizione da/verso gli Stati Uniti (es. pubblicazione su GitHub, server statunitensi). Va inviata **una volta**, al momento della pubblicazione del repository, e di nuovo solo se l'URL cambia.
- Testo tipo e indirizzi: crypt@bis.doc.gov e enc@nsa.gov, oggetto "TSU Notification", con nome del prodotto, URL del repository e indicazione che il codice è pubblicamente disponibile.

## Google Play

- Nella Play Console, alla domanda sull'uso di crittografia, va dichiarato che l'app usa crittografia e che rientra nell'eccezione per software pubblicamente disponibile/open source (o nell'esenzione per crittografia usata per proteggere dati dell'utente). Google richiede la conformità alle norme di esportazione degli Stati Uniti; la notifica TSU sopra ne è la base.
- Alcuni paesi possono essere esclusi dalla distribuzione in base alle sanzioni statunitensi; Google li gestisce automaticamente.

## Paesi di distribuzione (prima della produzione)

- Test interno: solo Italia (e i paesi di eventuali tester).
- Produzione: prima di scegliere i paesi, verificare le norme nazionali su distribuzione/importazione di crittografia. In particolare **Francia** (dichiarazione ANSSI per la fornitura di mezzi crittografici: verificare se si applica a software open source gratuito) e paesi con regole note sulla crittografia (es. Russia, Bielorussia, Kazakistan). I paesi sotto embargo USA sono esclusi automaticamente da Google.

## Azioni che restano all'utente (titolare della pubblicazione)

1. Al momento della pubblicazione su GitHub: inviare la notifica TSU (vedi sopra) e conservare la ricevuta in questo file (data e destinatari).
2. Nella Play Console: compilare la dichiarazione sulla crittografia.
3. Nessuna azione per F-Droid oltre alla pubblicazione del sorgente.

## Registro

| Data | Azione | Note |
|---|---|---|
| — | Notifica TSU | Da inviare quando il repository diventa pubblico |
