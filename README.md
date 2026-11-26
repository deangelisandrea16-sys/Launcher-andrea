# Launcher Andrea

Launcher Android personale per Redmi Note 15 5G (HyperOS 2).

## Installare
1. Apri **Releases** di questa repo dal telefono → ultima versione → scarica `launcher-andrea.apk`.
2. Aprilo e consenti l'installazione dal browser.
3. Premi il tasto Home → scegli **Launcher Andrea** → **Sempre**.

## Tornare al launcher Xiaomi (rollback)
Impostazioni → App → App predefinite → Schermata Home → **Avvio di sistema**.

## Funzioni
- Ora, data, prossima sveglia
- Ricerca istantanea (app + comandi), offline
- App principali fissabili (pressione lunga → Fissa/Rimuovi, Info, Disinstalla)
- Musica: play/pausa, avanti, indietro + Shazam
- Comandi rapidi: Sveglia, Hotspot, Spegni PC (da collegare)
- Tutte le app: scorri verso l'alto; le app nuove compaiono in cima con "Nuova"

## Firma
Gli aggiornamenti si installano sopra solo con una chiave stabile nei Secrets:
`KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
Senza, ogni versione va installata dopo aver disinstallato la precedente.
