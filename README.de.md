# DogmatixPlus

[English](README.md) · [Nederlands](README.nl.md) · [Français](README.fr.md) · **Deutsch** · [Español](README.es.md)

**Retro-Spiele auf deinem Android-Handy oder Handheld finden, herunterladen und ordnen.**

DogmatixPlus führt eine große, durchsuchbare Liste mit den Spielen aus den Quellen, die *du* hinzufügst, lädt sie in die richtigen Ordner herunter und hilft dir, deine Sammlung in Ordnung zu halten. Es funktioniert per Touch *und* mit einem Game-Controller und passt deshalb gut zu Handhelds wie Retroid, Anbernic oder Kinhank.

> **Die App enthält keine Spiele und keine Download-Links.** Du fügst deine eigenen Quellen hinzu, und du bist dafür verantwortlich, nur das herunterzuladen, was du besitzen darfst.

<table>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/library.png" width="210" alt="Die Bibliothek"><br><sub>Deine Spiele in einer Liste — ein grünes ✓ bedeutet, dass du das Spiel schon hast</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/duplicates.png" width="210" alt="Doppelte Spiele"><br><sub>Finde Spiele, die du doppelt hast</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/overview.png" width="210" alt="Bibliotheksübersicht"><br><sub>Sieh deine Sammlung pro Konsole</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/scan-progress.png" width="210" alt="Scan-Fortschritt"><br><sub>Sieh, wie weit ein Scan ist</sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/delete-dialog.png" width="210" alt="Löschbestätigung"><br><sub>Du siehst immer, welche Dateien gelöscht werden</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/settings.png" width="210" alt="Einstellungen"><br><sub>Die neuen Werkzeuge findest du in den Einstellungen</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/credits.png" width="210" alt="Mitwirkende"><br><sub>Anerkennung für alle Beteiligten</sub></td>
    <td></td>
  </tr>
</table>

<p align="center"><img src="docs/screenshots/library-landscape.png" width="720" alt="Die Bibliothek im Querformat auf einem Handheld"></p>

<p align="center"><img src="docs/screenshots/cloud-hub.png" width="460" alt="Die neue Cloud-Übersicht (Titel und Zahlen sind erfunden)"><br><sub>Die neue Cloud-Übersicht (Titel und Zahlen sind erfunden)</sub></p>

*Die Screenshots verwenden erfundene Spieltitel und leere Platzhalterdateien und zeigen die App auf Englisch.*

---

## Was kann die App?

### Neu in 2.2.0
- Geschwindigkeit und Restzeit pro Download, ehrliche Anzeigen bei unbekannter Größe und Fehlermeldungen mit konkreten nächsten Schritten.
- Nur reguläre Releases: 1.0.0, 1.1.0, …, 1.9.0, 2.0.0. [Versionstabelle](docs/releases/numbering.md).
- Von alten 8.x-Versionen einmal die [signierte APK 2.2.0](https://github.com/Tufein/DogmatixPlus/releases/download/v2.2.0/DogmatixPlus-release.apk) manuell installieren. Danach vergleichen Updates Android-Buildnummern.

### Neu in 1.8.0
- **Eine Seite für jedes Spiel**: A oder Tippen in der Bibliothek öffnet eine Vollbildseite mit dem Artwork, einer großen Download-Taste, der besten Version, Favorit, Sammlungen, Teilen und Entfernen, und Reitern für **Info**, **Versionen**, **Fortschritt** (Erfolge, Cloud-Spielstände) und **Mehr davon**. X oder langes Drücken öffnet weiterhin die schnelle Detailkarte.
- **Schnellmenü**: SELECT halten für einen Ring mit Suchen, Alles durchsuchen, Überrasch mich, Downloads, Alle pausieren / fortsetzen, Tools und Einstellungen. Kurz drücken setzt weiterhin einen Favoriten.
- **Intelligenter Speicher** (optional, *Tools → Speicher*): Konsolen, die du länger nicht gespielt hast und in denen kein Favorit liegt, ziehen als ganzer Ordner auf die SD-Karte und kommen zurück, sobald du sie wieder spielst. Jede Datei wird kopiert und geprüft, bevor das Original geht; *Prüfen* zeigt vorher, was verschoben würde. ES-DE folgt von selbst, andere Launcher musst du selbst auf den neuen Ordner zeigen lassen.
- **Alles durchsuchen**: eine Suche für Einstellungen, Tools, Bildschirme und deine Spiele, aus dem Schnellmenü, dem Tools-Titel oder den *Einstellungen*; eine gewählte Einstellung wird ins Bild geholt und hervorgehoben.
- **TV-Modus** (*Einstellungen → Aussehen und Bedienung*): größere Schrift und Zeilen, Ränder für den TV-Bildrand und Tasten der Fernbedienung; die App erscheint auch im Launcher von Android TV.
- **Text in den Einstellungen wird nie mehr abgeschnitten**: Titel, Hinweise und Werte brechen vollständig um.
- Noch nicht auf einem echten Gerät, einem Fernseher oder einer SD-Karte getestet.

### Neu in 1.6.0
- **Eine Sammlung auf dem Gerät halten** (*Tools → Sammlungen*): einschalten, und neue Spiele darin werden von selbst geladen, wenige pro Durchlauf und nur bei genug Platz. Nichts wird automatisch gelöscht; Spiele, die die Sammlung verlassen haben, stehen in einer Prüfliste.
- **Speicher freigeben** (*Tools*): nie gespielte Spiele, größte zuerst, mit dem gewonnenen Platz. Favoriten, Sammlungsspiele, RomM-Spielstände und Erfolge sind geschützt. Entfernen, oder entfernen und auf die Wunschliste setzen.
- **Beschreibungen für deinen Launcher** (*Tools*): schreibt Beschreibung, Genre, Jahr und Wertung in die gamelist.xml von ES-DE und die metadata.txt von Pegasus, ohne Vorhandenes anzutasten (vorher entsteht eine Sicherungskopie).
- **Beste Spiele je Konsole** (*Tools*, mit RetroAchievements-Schlüssel): die beliebtesten Spiele, was du hast, und eine Taste für das, was du noch holen kannst.
- **Bessere Version verfügbar** (*Tools*): eine neuere Revision, eine fertige Version statt einer Beta oder ein guter statt eines schlechten Dumps, für Spiele, die du schon hast.
- **Ein Wochenüberblick** (optional), eine **Schnelleinstellungs-Kachel** und Launcher-Verknüpfungen für die Downloads, **dein Jahr in Spielen** mit einer Karte zum Teilen.
- **Dasselbe Aussehen in jeder Zeile der Einstellungen** und dein eigenes Symbol auf dem zweiten Bildschirm.
- Noch nicht auf einem echten Gerät oder gegen einen echten RomM-, WebDAV- oder RetroAchievements-Server getestet.

### Neu in 1.5.0
- **Suchen nach Gefühl**: Filter für **Genre und Jahrzehnt** (aus den vorhandenen Spielinfos), **Mehr davon** in der Detailkarte, **Überrasch mich** auf der Start-Taste des Controllers und **kompakte Listen**.
- **Sammlungsziele und Spielverlauf** (*Tools*): wie vollständig jede Konsole ist, mit den fehlenden Titeln zum Import, und eine Zeitleiste pro Tag mit Geladenem und Gespieltem.
- **Spiel teilen** als Karte mit Cover, Wunschliste als Text teilen und ein Widget **Weiterspielen**. Ein gewünschtes Spiel, das auf deinem RomM-Server auftaucht, wird gemeldet.
- **Laden, wenn es passt**: nur im WLAN, beim Laden, heute Nacht oder zu einer festen Zeit, pro Spiel in der Detailkarte oder der Download-Liste.
- **Eine gemeinsame Wunschliste für die Familie** auf deinem eigenen WebDAV-Server, mit Angabe, wer ein Spiel hinzugefügt und wer es gefunden hat; WebDAV-Sync und -Backup sind sicherer (keine halb geschriebenen Dateien, keine neuere Version überschrieben, Warnung bei unverschlüsselten Adressen).
- **Alles prüfen** (*Tools*): ein Bericht zu Quellen, Speicher, BIOS, RomM, Backups, Benachrichtigungen und Akku, mit einer Lösung je Problem.
- Noch nicht auf einem echten Gerät oder gegen einen echten RomM- oder WebDAV-Server getestet.

### Neu in 1.4.0
- **Ein neues Aussehen**: Panels mit Tiefe, ein sanftes Leuchten in deiner Akzentfarbe, Konsolenfarben, ein Fokus, den man vom Sofa aus sieht, **Cover** (RomM, libretro-Boxart oder eine farbige Kachel), Diagramme, neue Symbole und ruhige Bewegung. Schalter für Animationen, Leuchten und Cover in der Liste stehen unter *Einstellungen → Aussehen und Bedienung*. Aufbau und Tasten sind unverändert.
- **Cloud-Übersicht** (*Einstellungen → Cloud*): RomM, Savesync, Cloud-Backup, Geräteabgleich, RetroAchievements und Debrid auf einem Bildschirm, dazu eine kleine **Wolke in der Leiste oben**, die Ruhe, Abgleich oder „braucht dich“ zeigt.
- **RomM bei jedem Spiel**: Zusammenfassung, Genres, Bewertung und Screenshots in der Detailkarte, dein **Spielstatus und deine Bewertung** werden zu RomM zurückgeschrieben, **Favoriten im Gleichschritt mit RomM** und **BIOS-Dateien aus RomM** (per MD5 geprüft).
- **Cloud-Saves pro Spiel**: die Saves und States auf dem Server (mit dem Screenshot des States) neben den Sicherheitskopien auf dem Gerät, mit **Wiederherstellen**, und eine Reihe **Weiterspielen** auf Home.
- **Deine eigene Cloud (WebDAV)**: ein **verschlüsseltes Backup** (AES-256-GCM, deine Passphrase) auf Nextcloud, ownCloud oder jeden WebDAV-Server, automatisch und mit Wiederherstellung, und **Geräteabgleich** für Favoriten, Wunschliste und Sammlungen zwischen deinen Handhelds.
- **RetroAchievements-Fortschritt** pro Spiel (Ring und Abzeichen) und dein Profil in der Übersicht.
- Noch nicht auf einem echten Gerät oder gegen einen echten RomM-, WebDAV- oder RetroAchievements-Server getestet.

### Neu in 1.3.0
- **Ganze Konsolen-Sets auf einmal**: *Alle laden* nimmt bis zu 3000 Spiele, ohne „App reagiert nicht“ während oder nach dem Stapel. Große Warteschlangen bleiben flüssig, lassen sich **anhalten**, zeigen die **Restzeit** und haben Knöpfe für die ganze Warteschlange (alle stoppen, fehlgeschlagene wiederholen, fertige entfernen).
- **Downloads kümmern sich selbst**: Fehlgeschlagene Downloads **versuchen es von selbst erneut**, Web-Downloads lassen sich **pausieren**, eine fertige Datei wird **mit deiner DAT verglichen**, und du bekommst **eine Benachrichtigung, wenn die Warteschlange fertig ist**.
- **Liste importieren** (*Tools*): eine Textdatei oder die Zwischenablage mit einem Spiel pro Zeile. Von jedem Spiel wird die beste Version auf einmal geladen, der Rest kann auf die Wunschliste. Die DAT-Prüfung nutzt das für die Spiele, die dir fehlen.
- **Letzte Suchen** unter dem Suchfeld, **Überrasch mich** (ein zufälliges Spiel aus der Liste) und eine Einstellung für die **Textgröße**.
- **Einstellungen und Tools in klaren Gruppen**, ein kurzes **Was ist neu** nach einem Update und ein **Frontend-Check** in den Tools.
- **Cover für Pegasus und RetroArch** neben denen für ES-DE, eine **Wunschliste, die weiß, was du schon hast** (und sich als Datei teilen lässt), **die Bibliothek auf einen anderen Speicher verschieben**, **hochladen, was RomM fehlt**, und **Spielstand-Sync für eigenständige Emulatoren** (DraStic, melonDS, mGBA, Snes9x EX+ und mehr).

### Neu in 1.2.0
- **Downloads machen dort weiter, wo sie aufgehört haben** (voller Speicher, geschlossene App, Neustart), und **die Warteschlange übersteht einen Neustart**; ein **Limit pro Server** hält strenge Server zufrieden. **Ordne die Warteschlange um** (▲ ▼, oder **Y**, um einen nach vorne zu holen) und **halte Speicher frei**, damit Downloads stoppen, bevor der Speicher voll ist.
- **Wunschliste auf Autopilot**, **.m3u-Playlists** für Spiele mit mehreren Discs und ein **Speicher-Ratgeber**, wenn „Alles Angezeigte laden“ nicht passt.
- **Cover für ES-DE** (und Cocoons ES-DE-Anbindung) nach jedem Download, von libretro-thumbnails. **Cocoon**: Dogmatix+ als Kacheln pro Konsole, gespeicherter Ansicht oder Downloads hinzufügen.
- **RetroAchievements-Abzeichen**, **Profile mit PIN**, **Statistiken** mit dem, was du in ES-DE spielst, und **gespeicherte Ansichten** mit einer Verknüpfung in ES-DE.
- **BIOS-Prüfung** für etwa zwanzig Systeme, **IPS- / UPS- / BPS-Patches** aus dem Dateimanager, und **an die App geteilte Links** landen direkt im Ordner einer Konsole.
- **RomM-Sammlungen** in beide Richtungen, **DAT direkt von Redump**, ein **zweiter Bildschirm** für Handhelds mit zwei Bildschirmen und Fernseher, eine wöchentliche **automatische Sicherung** und ein neues Symbol.

### Neu in 1.1.0
- **Schnellere Scans**: Quellen, deren Liste sich nicht geändert hat, werden übersprungen (6 Quellen mit 4.000 Spielen: von 18 s auf 2 s). Eine Quelle, die scheitert, behält ihre Spiele, und eine Webquelle kann **Ausweichadressen** haben.
- **Scannen im Hintergrund** (täglich, über WLAN, beim Laden, nachts) mit einer Meldung, wenn neue Spiele auftauchen; neue Spiele bekommen ein **Neu**-Abzeichen, einen Filter und die Sortierung *Neueste zuerst*.
- **Sammlungen**: eigene Listen neben den Favoriten.
- **Alles Angezeigte herunterladen**, nach einer Prüfung von Anzahl, Größe und freiem Speicher; auf Wunsch nur die beste Version jedes Spiels.
- **Nintendo-Switch-Updates und DLC**: sieh, welches Update oder DLC dir fehlt, und hol es.
- **DAT-Prüfung** mit No-Intro / Redump: gute Dumps, falsche Namen (mit einem Tipp umbenannt), unbekannte Dateien und fehlende Spiele.
- **Das Geschwindigkeitslimit funktioniert** (vorher nie) für alle Downloads zusammen, Torrents eingeschlossen, auf Wunsch nachts ohne Limit.
- Der **Dateimanager** kann umbenennen, verschieben und entpacken; **teile deine Quellen als QR-Code**; **installiere Updates aus der App**; ein **Startbildschirm-Widget**; ein **dicker Fokusrahmen** für Fernseher und Handheld.

### Spiele finden
- **Eine Liste** mit den Spielen aus all deinen Quellen.
- Eine **Suche**, die Fehler verzeiht: Akzente, Bindestriche und doppelte Buchstaben spielen keine Rolle, also findet „yugioh“ auch *Yu-Gi-Oh!*.
- **Filtern** nach Konsole, Region, Sprache und Typ sowie **Sortieren** nach Name oder Größe.
- Ein grünes **✓** markiert die Spiele, die du schon hast.
- **★ Favoriten**: Markiere Spiele, die dir gefallen, mit einem Stern und zeige nur diese an.

### Spiele herunterladen
- Funktioniert mit **Direktlinks, Torrents und Magnet-Links**.
- **Mehrere Downloads gleichzeitig**, mit Pausieren, Fortsetzen, Wiederholen und einem Geschwindigkeitslimit.
- **ZIP- und 7z-Dateien werden für dich entpackt.**
- Jede Konsole bekommt **einen eigenen Ordner**. Ordner, die du schon hast (wie `gba` oder `psx`), werden weiterverwendet, und du kannst zwei Ordner zusammenführen, die dieselbe Konsole meinen.
- Deine **Downloadliste bleibt erhalten**, wenn du die App schließt. Wähle mehrere Downloads aus, um sie gemeinsam zu stoppen, zu wiederholen oder zu löschen.
- Optional: **TorBox** und **Real-Debrid** — kostenpflichtige Dienste, die Torrents für dich holen, sodass der Download eine ganz normale, schnelle Datei ist.
- **Nur im WLAN, nur beim Laden oder nur nachts**: Neue Downloads warten, bis deine Bedingungen erfüllt sind, und sagen, worauf sie warten. Eine **Prüfsummen-Kontrolle** vergleicht eine fertige Datei mit dem Hash, den die Quelle veröffentlicht. In den Spielinfos wählt **Beste Version** die passende Version (deine Region und Sprache, keine Demos). Ein fertiger Download lässt sich in einem Emulator **öffnen**.

### Deine Sammlung in Ordnung halten *(neu in DogmatixPlus)*
- **Doppelte Spiele**: findet Spiele, die mehr als einmal auf deinem Gerät sind, zeigt, wie viel Speicherplatz du gewinnst, und lässt dich die überzählige Kopie löschen. Nichts wird gelöscht, bevor du genau gesehen hast, welche Dateien verschwinden.
- **Bibliotheksübersicht**: für jede Konsole, wie viele Spiele aufgelistet sind, wie viele du besitzt, wie viele auf deinem Gerät sind und wie groß das ist, und wann zuletzt gescannt wurde.
- **Sichern und Sicherung wiederherstellen**: Speichere deine Einstellungen, Quellen, Favoriten und Downloads in einer Datei und spiele sie später wieder ein — praktisch für ein neues Gerät.
- **Scan-Fortschritt**: eine Prozentzahl und die verbleibende Zeit, während deine Quellen gelesen werden. Quellen werden **parallel** gescannt, das geht schnell.
- **Spielsets**: findet Disc-Images, die nicht laufen (eine `.cue`, deren Track fehlt, eine Playlist mit einer gelöschten Disc), und erstellt `.m3u`-Playlists für Spiele mit mehreren Discs.
- **Speicher**: Platz pro Konsole, deine größten Spiele und ob die Downloads in der Warteschlange noch passen.
- **Wunschliste**: notiere Spiele, die du haben willst; du bekommst eine Meldung, sobald eines in deinen Quellen auftaucht.
- **Exportiere** deine Sammlung als Tabelle (CSV) oder Webseite. Die Duplikatsuche kann auch **vorschlagen, welche Kopie bleibt**.
- **Dateimanager**: schau in deine Ordner, sieh Größen und welche Dateien als Spiele zählen, prüfe Disc-Sets, öffne oder lösche Dateien.
- **Scan-Bericht**: nach einem Scan eine Übersicht der fehlgeschlagenen Quellen mit Grund und *Diese erneut scannen*; jede Quelle zeigt ihr letztes Ergebnis.

### Gemacht für Handhelds
- **Alles mit dem Gamepad steuern**: D-Pad, A/B/X/Y und die Schultertasten. Hinweise am unteren Bildschirmrand passen zu deinem Pad (Xbox, Nintendo oder PlayStation), und du kannst die Tasten tauschen, wenn dein Pad sie andersherum meldet.
- Layouts für **Quer- und Hochformat**, mit einem Filterfeld neben der Liste auf breiten Bildschirmen.
- **Hell, Dunkel oder Echtes Schwarz** als Design (schön auf OLED-Bildschirmen), **zwölf Akzentfarben** und **Material You** (Android 12+: die Farben folgen deinem Hintergrundbild).
- **◀ ▶ springt in der Bibliothek nach Anfangsbuchstaben**, praktisch bei langen Listen ohne Touchscreen.

### Funktioniert mit deinem Spiele-Launcher
- **ES-DE** und **iiSU** bekommen in jeder Konsole einen Eintrag „Search for more games“, der mit einem Knopfdruck eingerichtet wird. **Daijishō** zeigt dir die wenigen Werte, die du eintippen musst.

### Funktioniert mit RomM
- Schicke fertige Downloads an deinen **RomM**-Server oder nutze RomM als Quelle für Spiele.
- **Spielstände synchronisieren**: Dein RomM-Server bewahrt deine **Spielstände und Savestates** auf. Wähle die Ordner, in die dein Emulator speichert (bei RetroArch: `saves` und `states`), und DogmatixPlus lädt neuen Fortschritt hoch und holt neueren Fortschritt — von einem anderen Handheld oder aus RomMs Web-Player — herunter. Hat sich ein Spielstand auf beiden Seiten geändert, entscheidest du, welcher bleibt; eine ersetzte Kopie wird 30 Tage aufbewahrt. Das kann automatisch passieren, wenn du die App öffnest oder aus einem Spiel zurückkommst (*Einstellungen → Spielstände synchronisieren*).
- Spiele, die dein RomM-Server schon hat, werden in der Bibliothek **markiert**. Ein Heimserver mit **selbst signiertem Zertifikat** funktioniert, sobald du den Fingerabdruck geprüft hast. Ein unterbrochener Upload **setzt sich fort**, wo er stehen blieb. Die **Cover** von RomM lassen sich in ES-DE holen.
- Die Spielstand-Synchronisierung kann alle paar Stunden **im Hintergrund** laufen und auch **Löschungen übernehmen** (standardmäßig aus, mit Sicherungen). Bei einem auf beiden Seiten geänderten Spielstand siehst du jetzt beide Zeiten und Größen.

### Deine Quellen, auf deine Art
- Füge Quellen von Hand hinzu oder **importiere und exportiere** sie als Datei, um sie zwischen Geräten zu teilen. Der Export nimmt jetzt auch deine **★ Favoriten** mit, und der Import fügt sie auf dem anderen Gerät hinzu.
- Eine kurze **Einführung beim ersten Start** hilft dir, deinen ROM-Ordner zu wählen und deine Quellen zu importieren.

### Sprachen
- **Englisch, Spanisch, Niederländisch, Französisch, Deutsch, Italienisch und Portugiesisch** (*Einstellungen → Sprache* oder automatisch die Sprache deines Handys).

---

## Installation

1. Öffne die **[Releases-Seite](https://github.com/Tufein/DogmatixPlus/releases)** auf deinem Handy oder Handheld, oder lade die Datei dort herunter und kopiere sie auf dein Gerät.
2. Lade eine der beiden Dateien herunter:
   - **`DogmatixPlus-release.apk`** — *die normale Wahl.* Die App heißt **Dogmatix+** und hat einen eigenen Paketnamen: Sie wird **neben** dem offiziellen Dogmatix und älteren DogmatixPlus-Versionen installiert, nichts von deinen Sachen wird angefasst.
   - **`DogmatixPlus-debug.apk`** — eine Debug-Version, die ebenfalls neben allem anderen installiert wird.
   - **Du kommst von einem älteren DogmatixPlus?** In der alten App *Einstellungen → Sichern*, dann in Dogmatix+ *Einstellungen → Sicherung wiederherstellen*, und richte ES-DE / iiSU / Daijishō noch einmal ein.
3. Öffne die Datei und erlaube **„Unbekannte Apps installieren“**, wenn Android danach fragt.

Du brauchst **Android 10 oder neuer**. Die App ist nicht bei Google Play erhältlich.

## Erster Start

1. Die Einführung erklärt die Grundlagen.
2. **Wähle deinen ROM-Ordner** — den Ordner, in den deine Spiele sollen.
3. **Importiere deine Quellen** (eine Datei mit deinen Spielelisten) — oder überspringe das und füge Quellen später im Tab **Quellen** hinzu.
4. Öffne die **Bibliothek** und tippe auf ein Spiel (oder drücke **A**), um seine Spielseite zu öffnen. Wähle dort **Herunterladen** oder **Spielen**.

## Ein Gamepad benutzen

| Taste | Was sie macht |
|---|---|
| D-Pad | Sich bewegen |
| **A** | Auswählen / Spielseite öffnen; dort herunterladen oder spielen |
| **B** | Einen Schritt zurück |
| **X** | Spielinfos |
| **Y** | Suchen |
| **Select** | Ein Spiel mit einem Stern markieren oder den Stern entfernen |
| **LB / RB** | Zwischen den Filtern und der Liste wechseln |
| **ZL / ZR** | Voriger / nächster Bereich |
| **R3** | Filter ausblenden oder einblenden |

Alles funktioniert auch per Touch. Die Hinweise erscheinen nur, solange ein Controller angeschlossen ist.

## Gut zu wissen

- Entfernte Spiele kommen in den Papierkorb. Unter Werkzeuge → Papierkorb und Wiederherstellung wiederherstellen oder leeren. Speicher wird erst nach dem Leeren frei; der Datei-Explorer löscht weiterhin endgültig.
- **Eine Sicherungsdatei enthält deine Kontoschlüssel** (TorBox, Real-Debrid, RomM). Gib sie nicht weiter.
- **Das Fenster mit den Spielinfos bleibt in den hier angebotenen Downloads leer**, weil es einen kostenlosen Schlüssel aus einer Spieldatenbank braucht, der erst beim Erstellen der App hinzugefügt wird.
- DogmatixPlus sucht nicht von selbst nach Spielen. Es liest nur die Quellen, die **du** hinzufügst.
- **Alle öffentlichen Releases sind reguläre Releases.** Die Versionen folgen 1.0.0, 1.1.0, …, 1.9.0, 2.0.0. Siehe [Versionsnummerierung](docs/releases/numbering.md).
- **Etwas funktioniert nicht?** *Einstellungen → Diagnose teilen* erstellt einen Textbericht für eine Fehlermeldung; Tokens, Serveradressen und Magnet-Links werden vorher entfernt.

---

## Mitwirkende

DogmatixPlus ist eine kleine Ergänzung auf Basis von zwei anderen Projekten. Das meiste, was du jeden Tag benutzt, stammt von ihnen.

| Projekt | Von | Was es beigesteuert hat |
|---|---|---|
| **[Milou](https://github.com/santiifm/milou)** | [santiifm](https://github.com/santiifm) | Die ursprüngliche App und ihr ganzer Kern: Quellen lesen, Spiele nach Konsole / Region / Sprache sortieren, suchen, herunterladen und entpacken. |
| **[Dogmatix](https://github.com/cortinadev/dogmatix)** | [Rafa Cortina](https://github.com/cortinadev) | Die Handheld-Version: Gamepad-Steuerung, Querformat-Layout, Designs, Favoriten, Pausieren und Fortsetzen, die Einführung beim ersten Start, ES-DE / iiSU / Daijishō, TorBox und Real-Debrid, RomM. |
| **DogmatixPlus** | [Tufein](https://github.com/Tufein) | Suche nach doppelten Spielen, Bibliotheksübersicht, Sichern und Sicherung wiederherstellen, Scan-Fortschritt, Spielstände mit RomM synchronisieren, Spielset-Prüfung, Speicherübersicht, Wunschliste, Export, Download-Zeitplan, Prüfsummen-Kontrolle, die niederländische, französische, deutsche, italienische und portugiesische Übersetzung und diese Veröffentlichungen. |

DogmatixPlus wurde **mit Hilfe von KI entwickelt**: Der Code, die Tests und die Dokumentation wurden zusammen mit einem KI-Assistenten geschrieben und in mehreren Prüfrunden kontrolliert. Entscheidungen, Ausrichtung und Veröffentlichung liegen beim Betreuer des Projekts.

## Haftungsausschluss

Diese App dient nur zu Bildungszwecken. Du bist dafür verantwortlich, sicherzustellen, dass du rechtlich dazu berechtigt bist, jegliche Inhalte herunterzuladen.

Milou und Dogmatix haben keine Lizenz, daher bleiben alle Rechte an ihrem Code bei ihren Autoren. DogmatixPlus ist eine inoffizielle persönliche Anpassung und steht mit keinem der beiden Projekte in Verbindung. Wenn du einer der ursprünglichen Autoren bist und etwas geändert oder entfernt haben möchtest, eröffne bitte ein Issue.

---

## Für Entwickler

Die technischen Details — wie die App aufgebaut ist, wie die Suche nach doppelten Spielen entscheidet, die Ordnerstruktur, Deep Links, der Tech-Stack und mehr — stehen in **[TECHNICAL.md](TECHNICAL.md)**. Siehe auch [FRONTENDS.md](FRONTENDS.md) für die Einrichtung der Launcher und [CHANGELOG.md](CHANGELOG.md) für jede Änderung. TECHNICAL.md ist auf Englisch.
