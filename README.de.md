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

*Die Screenshots verwenden erfundene Spieltitel und leere Platzhalterdateien und zeigen die App auf Englisch.*

---

## Was kann die App?

### Neu in 3.0
- **Downloads machen dort weiter, wo sie stoppten** (Speicher voll, App geschlossen, Neustart), und **die Warteschlange übersteht einen Neustart**; ein **Limit pro Server** verhindert Sperren.
- **Wunschliste auf Autopilot**, **.m3u-Playlists** für Spiele mit mehreren Discs und ein **Speicherberater**, wenn „Alles Angezeigte herunterladen“ nicht passt.
- **Cover für ES-DE** (und Cocoons ES-DE-Verknüpfung) nach jedem Download, von libretro-thumbnails.
- **RetroAchievements-Abzeichen**, **Profile mit PIN** und **was du spielst** aus ES-DE in den Statistiken.

### Neu in 2.6
- **Keine Zwangsbeendigungen mehr bei großen Download-Warteschlangen**: Hunderte wartende Downloads bleiben flüssig.
- **Cocoon**: Dogmatix+ als Kacheln pro Konsole, gespeicherter Ansicht oder Downloads (*Einstellungen → Cocoon*); dieselben Verknüpfungen erscheinen bei langem Drücken aufs Symbol.
- **Ein neues Symbol**: ein Modul mit Hundeohren und einem Download-Pfeil.

### Neu in 2.5
- **BIOS-Prüfung**: sieh pro Konsole, ob die BIOS-Dateien deines Emulators da sind und die richtigen Dumps sind (rund zwanzig Systeme).
- **IPS- / UPS- / BPS-Patches anwenden** aus dem Dateimanager; das gepatchte Spiel ist eine neue Kopie, das Original bleibt.
- **Links an die App teilen**: ein Download-Link oder Magnet aus dem Browser landet direkt im Ordner einer Konsole oder wird eine Quelle.
- **Die Download-Warteschlange umsortieren** (▲ ▼, oder **Y**, um einen nach vorn zu holen), **freien Speicher behalten**, damit Downloads stoppen, bevor der Speicher voll ist, und Downloads, die auf die **Ausweichadressen** einer Quelle ausweichen.
- **Gespeicherte Ansichten**: speichere deine Filter unter einem Namen und lege eine Verknüpfung dazu in ES-DE ab; **ES-DE-Favoriten** bekommen hier einen Stern.
- **Statistiken**, **RomM-Sammlungen** in beide Richtungen, **DAT direkt von Redump**, ein **zweiter Bildschirm** für Doppelbildschirm-Handhelds und Fernseher und eine wöchentliche **automatische Sicherung**.

### Neu in 2.0
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
- **Nur im WLAN, nur beim Laden oder nur nachts** *(neu, Alpha)*: Neue Downloads warten, bis deine Bedingungen erfüllt sind, und sagen, worauf sie warten. Eine **Prüfsummen-Kontrolle** vergleicht eine fertige Datei mit dem Hash, den die Quelle veröffentlicht. In den Spielinfos wählt **Beste Version** die passende Version (deine Region und Sprache, keine Demos). Ein fertiger Download lässt sich in einem Emulator **öffnen**.

### Deine Sammlung in Ordnung halten *(neu in DogmatixPlus)*
- **Doppelte Spiele**: findet Spiele, die mehr als einmal auf deinem Gerät sind, zeigt, wie viel Speicherplatz du gewinnst, und lässt dich die überzählige Kopie löschen. Nichts wird gelöscht, bevor du genau gesehen hast, welche Dateien verschwinden.
- **Bibliotheksübersicht**: für jede Konsole, wie viele Spiele aufgelistet sind, wie viele du besitzt, wie viele auf deinem Gerät sind und wie groß das ist, und wann zuletzt gescannt wurde.
- **Sichern und Sicherung wiederherstellen**: Speichere deine Einstellungen, Quellen, Favoriten und Downloads in einer Datei und spiele sie später wieder ein — praktisch für ein neues Gerät.
- **Scan-Fortschritt**: eine Prozentzahl und die verbleibende Zeit, während deine Quellen gelesen werden. Seit 1.2.0 werden Quellen **parallel** und viel schneller gescannt.
- **Spielsets** *(neu, Alpha)*: findet Disc-Images, die nicht laufen (eine `.cue`, deren Track fehlt, eine Playlist mit einer gelöschten Disc), und erstellt `.m3u`-Playlists für Spiele mit mehreren Discs.
- **Speicher**: Platz pro Konsole, deine größten Spiele und ob die Downloads in der Warteschlange noch passen.
- **Wunschliste**: notiere Spiele, die du haben willst; du bekommst eine Meldung, sobald eines in deinen Quellen auftaucht.
- **Exportiere** deine Sammlung als Tabelle (CSV) oder Webseite. Die Duplikatsuche kann auch **vorschlagen, welche Kopie bleibt**.
- **Dateimanager** *(1.3)*: schau in deine Ordner, sieh Größen und welche Dateien als Spiele zählen, prüfe Disc-Sets, öffne oder lösche Dateien.
- **Scan-Bericht** *(1.3)*: nach einem Scan eine Übersicht der fehlgeschlagenen Quellen mit Grund und *Diese erneut scannen*; jede Quelle zeigt ihr letztes Ergebnis.

### Gemacht für Handhelds
- **Alles mit dem Gamepad steuern**: D-Pad, A/B/X/Y und die Schultertasten. Hinweise am unteren Bildschirmrand passen zu deinem Pad (Xbox, Nintendo oder PlayStation), und du kannst die Tasten tauschen, wenn dein Pad sie andersherum meldet.
- Layouts für **Quer- und Hochformat**, mit einem Filterfeld neben der Liste auf breiten Bildschirmen.
- **Hell, Dunkel oder Echtes Schwarz** als Design (schön auf OLED-Bildschirmen), **zwölf Akzentfarben** und **Material You** (Android 12+: die Farben folgen deinem Hintergrundbild).
- **◀ ▶ springt in der Bibliothek nach Anfangsbuchstaben**, praktisch bei langen Listen ohne Touchscreen.

### Funktioniert mit deinem Spiele-Launcher
- **ES-DE** und **iiSU** bekommen in jeder Konsole einen Eintrag „Search for more games“, der mit einem Knopfdruck eingerichtet wird. **Daijishō** zeigt dir die wenigen Werte, die du eintippen musst.

### Funktioniert mit RomM
- Schicke fertige Downloads an deinen **RomM**-Server oder nutze RomM als Quelle für Spiele.
- **Spielstände synchronisieren** *(neu, Beta)*: Dein RomM-Server bewahrt deine **Spielstände und Savestates** auf. Wähle die Ordner, in die dein Emulator speichert (bei RetroArch: `saves` und `states`), und DogmatixPlus lädt neuen Fortschritt hoch und holt neueren Fortschritt — von einem anderen Handheld oder aus RomMs Web-Player — herunter. Hat sich ein Spielstand auf beiden Seiten geändert, entscheidest du, welcher bleibt; eine ersetzte Kopie wird 30 Tage aufbewahrt. Das kann automatisch passieren, wenn du die App öffnest oder aus einem Spiel zurückkommst (*Einstellungen → Spielstände synchronisieren*).
- *(neu, Alpha)* Spiele, die dein RomM-Server schon hat, werden in der Bibliothek **markiert**. Ein Heimserver mit **selbst signiertem Zertifikat** funktioniert, sobald du den Fingerabdruck geprüft hast. Ein unterbrochener Upload **setzt sich fort**, wo er stehen blieb. Die **Cover** von RomM lassen sich in ES-DE holen.
- *(neu, Alpha)* Die Spielstand-Synchronisierung kann alle paar Stunden **im Hintergrund** laufen und auch **Löschungen übernehmen** (standardmäßig aus, mit Sicherungen). Bei einem auf beiden Seiten geänderten Spielstand siehst du jetzt beide Zeiten und Größen.

### Deine Quellen, auf deine Art
- Füge Quellen von Hand hinzu oder **importiere und exportiere** sie als Datei, um sie zwischen Geräten zu teilen. Der Export nimmt jetzt auch deine **★ Favoriten** mit, und der Import fügt sie auf dem anderen Gerät hinzu.
- Eine kurze **Einführung beim ersten Start** hilft dir, deinen ROM-Ordner zu wählen und deine Quellen zu importieren.

### Sprachen
- **Englisch, Spanisch, Niederländisch, Französisch, Deutsch, Italienisch und Portugiesisch** (*Einstellungen → Sprache* oder automatisch die Sprache deines Handys).

---

## Installation

1. Öffne die **[Releases-Seite](https://github.com/Tufein/DogmatixPlus/releases)** auf deinem Handy oder Handheld, oder lade die Datei dort herunter und kopiere sie auf dein Gerät.
2. Lade eine der beiden Dateien herunter:
   - **`DogmatixPlus-release.apk`** — *die normale Wahl.* Seit 1.2.0 heißt die App **Dogmatix+** und hat einen eigenen Paketnamen: Sie wird **neben** dem offiziellen Dogmatix und älteren DogmatixPlus-Versionen installiert, nichts von deinen Sachen wird angefasst.
   - **`DogmatixPlus-debug.apk`** — eine Debug-Version, die ebenfalls neben allem anderen installiert wird.
   - **Du kommst von einem älteren DogmatixPlus?** In der alten App *Einstellungen → Sichern*, dann in Dogmatix+ *Einstellungen → Sicherung wiederherstellen*, und richte ES-DE / iiSU / Daijishō noch einmal ein.
3. Öffne die Datei und erlaube **„Unbekannte Apps installieren“**, wenn Android danach fragt.

Du brauchst **Android 10 oder neuer**. Die App ist nicht bei Google Play erhältlich.

## Erster Start

1. Die Einführung erklärt die Grundlagen.
2. **Wähle deinen ROM-Ordner** — den Ordner, in den deine Spiele sollen.
3. **Importiere deine Quellen** (eine Datei mit deinen Spielelisten) — oder überspringe das und füge Quellen später im Tab **Quellen** hinzu.
4. Öffne die **Bibliothek**, suche dir ein Spiel aus und tippe darauf (oder drücke **A**), um es herunterzuladen.

## Ein Gamepad benutzen

| Taste | Was sie macht |
|---|---|
| D-Pad | Sich bewegen |
| **A** | Auswählen / herunterladen |
| **B** | Einen Schritt zurück |
| **X** | Spielinfos |
| **Y** | Suchen |
| **Select** | Ein Spiel mit einem Stern markieren oder den Stern entfernen |
| **LB / RB** | Zwischen den Filtern und der Liste wechseln |
| **ZL / ZR** | Voriger / nächster Bereich |
| **R3** | Filter ausblenden oder einblenden |

Alles funktioniert auch per Touch. Die Hinweise erscheinen nur, solange ein Controller angeschlossen ist.

## Gut zu wissen

- **Das Löschen von Duplikaten ist endgültig.** Die App zeigt dir vorher jede Datei, aber es gibt keinen Papierkorb.
- **Eine Sicherungsdatei enthält deine Kontoschlüssel** (TorBox, Real-Debrid, RomM). Gib sie nicht weiter.
- **Das Fenster mit den Spielinfos bleibt in den hier angebotenen Downloads leer**, weil es einen kostenlosen Schlüssel aus einer Spieldatenbank braucht, der erst beim Erstellen der App hinzugefügt wird.
- DogmatixPlus sucht nicht von selbst nach Spielen. Es liest nur die Quellen, die **du** hinzufügst.
- **Die neuesten Versionen sind Vorabversionen** (Alpha, Beta). Die Update-Prüfung der App überspringt sie, außer du schaltest *Einstellungen → Vorabversionen einbeziehen* ein.
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
