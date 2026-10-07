# DogmatixPlus

[English](README.md) · **Nederlands** · [Français](README.fr.md) · [Deutsch](README.de.md) · [Español](README.es.md)

**Vind, download en organiseer retrogames op je Android-telefoon of handheld.**

DogmatixPlus houdt één grote, doorzoekbare lijst bij van de games uit de bronnen die *jij* toevoegt, downloadt ze in de juiste mappen en helpt je om je collectie netjes te houden. Het werkt met het touchscreen *en* met een gamecontroller, dus het is ook op handhelds zoals de Retroid, Anbernic of Kinhank helemaal op zijn plek.

> **De app wordt zonder games of downloadlinks geleverd.** Je voegt je eigen bronnen toe en je bent er zelf verantwoordelijk voor dat je alleen downloadt wat je mag hebben.

<table>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/library.png" width="210" alt="De bibliotheek"><br><sub>Je games in één lijst — een groene ✓ betekent dat je de game al hebt</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/duplicates.png" width="210" alt="Dubbele games"><br><sub>Vind games die je dubbel hebt</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/overview.png" width="210" alt="Bibliotheekoverzicht"><br><sub>Bekijk je collectie per console</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/scan-progress.png" width="210" alt="Scanvoortgang"><br><sub>Zie hoe ver een scan is</sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/delete-dialog.png" width="210" alt="Bevestiging van het verwijderen"><br><sub>Je ziet altijd welke bestanden verdwijnen</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/settings.png" width="210" alt="Instellingen"><br><sub>De nieuwe hulpmiddelen staan in Instellingen</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/credits.png" width="210" alt="Credits"><br><sub>Credits voor iedereen die erbij betrokken was</sub></td>
    <td></td>
  </tr>
</table>

<p align="center"><img src="docs/screenshots/library-landscape.png" width="720" alt="De bibliotheek in liggende stand op een handheld"></p>

<p align="center"><img src="docs/screenshots/cloud-hub.png" width="460" alt="Het nieuwe cloud-overzicht (titels en cijfers zijn verzonnen)"><br><sub>Het nieuwe cloud-overzicht (titels en cijfers zijn verzonnen)</sub></p>

*De screenshots gebruiken verzonnen gametitels en lege dummybestanden en tonen de app in het Engels.*

---

## Wat kan het?

### Nieuw in 2.3.0

- Zoek downloads op titel of bestandsnaam, filter op status en voer batchacties uit op de resultaten. Zet wachtende selecties samen vooraan.
- Veiliger hervatten en controle op volledige bestanden; oude retrytimers kunnen een nieuwe poging niet onverwacht herstarten.

### Nieuw in 2.2.0
- **Duidelijkere downloads:** snelheid en resterende tijd per bestand, eerlijke informatie bij onbekende grootte of stilstand, en foutmeldingen met een vervolgstap.
- **Alle releases zijn regulier:** 1.0.0, 1.1.0, …, 1.9.0, 2.0.0. Zie de [versietabel](docs/releases/numbering.md).
- **Overstappen vanaf de oude 8.x-versies:** installeer eenmalig de [ondertekende APK van 2.2.0](https://github.com/Tufein/DogmatixPlus/releases/download/v2.2.0/DogmatixPlus-release.apk). Daarna gebruikt de updatecontrole Android-buildnummers.

### Nieuw in 1.8.0
- **Een pagina voor elke game**: A of een tik in de bibliotheek opent een pagina over het hele scherm met de art, een grote downloadknop, de beste versie, favoriet, collecties, delen en verwijderen, en tabbladen **Over**, **Versies**, **Voortgang** (achievements, cloud-saves) en **Meer zoals dit**. X of lang indrukken opent nog steeds de snelle detailkaart.
- **Snelmenu**: houd SELECT ingedrukt voor een ring met Zoeken, Alles doorzoeken, Verras me, Downloads, Alles pauzeren / hervatten, Tools en Instellingen. Kort drukken maakt nog steeds een favoriet.
- **Slimme opslag** (optioneel, *Tools → Opslag*): consoles die je een tijd niet speelde en waarin geen favoriet staat verhuizen als hele map naar de SD-kaart, en komen terug zodra je ze weer speelt. Elk bestand wordt gekopieerd en gecontroleerd voordat het origineel weggaat; *Bekijken* laat eerst zien wat zou verhuizen. ES-DE volgt vanzelf, andere launchers moet je zelf naar de nieuwe map wijzen.
- **Alles doorzoeken**: één zoekveld voor instellingen, tools, schermen en je games, vanuit het snelmenu, de titel van Tools of de *Instellingen*; een gekozen instelling wordt in beeld gebracht en licht op.
- **Tv-modus** (*Instellingen → Uiterlijk en bediening*): grotere tekst en rijen, marges voor de randen van de tv en toetsen van de afstandsbediening; de app staat ook in de launcher van Android TV.
- **Tekst in de instellingen wordt nooit meer afgekapt**: titels, uitleg en waarden lopen volledig door.
- Nog niet getest op een echt apparaat, een tv of een SD-kaart.

### Nieuw in 1.6.0
- **Houd een collectie op je toestel** (*Tools → Collecties*): zet hem aan en nieuwe games in die collectie worden vanzelf gedownload, een paar per keer en alleen als er ruimte is. Er wordt niets automatisch verwijderd; games die uit de collectie zijn gehaald staan in een controlelijst.
- **Ruimte vrijmaken** (*Tools*): games die je nooit speelde, grootste eerst, met de ruimte die je wint. Favorieten, games in een collectie, RomM-saves en achievements zijn beschermd. Verwijder ze, of verwijder en zet ze op de verlanglijst.
- **Beschrijvingen voor je launcher** (*Tools*): schrijft beschrijving, genre, jaar en waardering in de gamelist.xml van ES-DE en de metadata.txt van Pegasus, zonder te raken aan wat er staat (eerst komt er een reservekopie).
- **Beste games per console** (*Tools*, met een RetroAchievements-sleutel): de meest geliefde games, wat je hebt, en een knop voor wat je nog kunt halen.
- **Betere versie beschikbaar** (*Tools*): een nieuwere revisie, een eindversie in plaats van een beta, of een goede dump in plaats van een slechte, voor games die je al hebt.
- **Een wekelijks overzicht** (optioneel), een **Snelle instellingen-tegel** en launcher-snelkoppelingen voor de downloads, en **jouw jaar in games** met een kaart om te delen.
- **Dezelfde look in elke rij van de instellingen**, en jouw eigen icoon op het tweede scherm.
- Nog niet getest op een echt apparaat of tegen een echte RomM-, WebDAV- of RetroAchievements-server.

### Nieuw in 1.5.0
- **Zoeken op gevoel**: filters voor **genre en decennium** (uit de gameinformatie die je al hebt), **Meer zoals dit** in de detailkaart, **Verras me** op de Start-knop van de controller en een optie voor **compacte lijsten**.
- **Verzamelingsdoelen en speelgeschiedenis** (*Tools*): hoe compleet elke console is, met de ontbrekende titels klaar om te importeren, en een tijdlijn per dag van wat je downloadde en speelde.
- **Een game delen** als kaart met cover, de verlanglijst delen als tekst, en een **Verder spelen**-widget voor het startscherm. Een gewenste game die op je RomM-server verschijnt wordt gemeld.
- **Downloaden wanneer het uitkomt**: alleen via wifi, aan de lader, vanavond of op een vast tijdstip, per game vanuit de detailkaart of de downloadlijst.
- **Een gedeelde verlanglijst voor het gezin** op je eigen WebDAV-server, met wie een game toevoegde en wie hem vond; de WebDAV-sync en -back-up zijn veiliger (geen half geschreven bestanden, geen nieuwere versie overschrijven, een waarschuwing voor onversleutelde adressen).
- **Alles controleren** (*Tools*): één rapport over bronnen, opslag, BIOS, RomM, back-ups, meldingen en batterij, met een oplossing per probleem.
- Nog niet getest op een echt apparaat of tegen een echte RomM- of WebDAV-server.

### Nieuw in 1.4.0
- **Een nieuw uiterlijk**: panelen met diepte, een zachte gloed in je accentkleur, consolekleuren, een focus die je vanaf de bank ziet, **covers** (RomM, libretro-boxart of een gekleurde tegel), grafieken, nieuwe pictogrammen en rustige beweging. Schakelaars voor animaties, gloed en covers in de lijst staan in *Instellingen → Uiterlijk en bediening*. Indeling en knoppen zijn niet veranderd.
- **Cloud-overzicht** (*Instellingen → Cloud*): RomM, save-sync, cloud-back-up, apparaten synchroniseren, RetroAchievements en Debrid op één scherm, en een klein **wolkje in de bovenbalk** dat rust, synchroniseren of "heeft jou nodig" toont.
- **RomM bij elke game**: samenvatting, genres, beoordeling en screenshots in de detailkaart, je **speelstatus en cijfer** teruggeschreven naar RomM, **favorieten gelijk met RomM** en **BIOS-bestanden uit RomM** (gecontroleerd met MD5).
- **Cloudsaves per game**: de saves en states op de server (met de screenshot van de state) naast de veiligheidskopieën op het apparaat, met **Terugzetten**, en een rij **Verder spelen** op Home.
- **Je eigen cloud (WebDAV)**: een **versleutelde back-up** (AES-256-GCM, jouw wachtwoordzin) naar Nextcloud, ownCloud of elke WebDAV-server, automatisch en met herstel, en **apparaten synchroniseren** voor favorieten, verlanglijst en collecties tussen je handhelds.
- **RetroAchievements-voortgang** per game (ring en badges) en je profiel in het overzicht.
- Nog niet getest op een echt apparaat of tegen een echte RomM-, WebDAV- of RetroAchievements-server.

### Nieuw in 1.3.0
- **Hele consolesets in één keer**: *Alles downloaden* neemt tot 3000 games, zonder "app reageert niet" tijdens of na de batch. Grote wachtrijen blijven vlot, kunnen **gepauzeerd** worden, tonen de **resterende tijd** en hebben knoppen voor de hele wachtrij (alles stoppen, mislukte opnieuw, voltooide wissen).
- **Downloads zorgen voor zichzelf**: mislukte downloads **proberen het zelf opnieuw**, webdownloads kun je **pauzeren**, een klaar bestand wordt **met je DAT vergeleken**, en je krijgt **een melding als de wachtrij klaar is**.
- **Lijst importeren** (*Tools*): een tekstbestand of het klembord met één game per regel. Van elke game wordt de beste versie in één keer gedownload, de rest kan op de verlanglijst. De DAT-controle gebruikt dit voor de games die je mist.
- **Recente zoekopdrachten** onder het zoekveld, **Verras me** (een willekeurige game uit de lijst) en een instelling voor de **tekstgrootte**.
- **Instellingen en Tools in duidelijke groepen**, een kort **wat is nieuw** na een update, en een **frontend-check** in Tools.
- **Covers voor Pegasus en RetroArch** naast die voor ES-DE, een **verlanglijst die weet wat je al hebt** (en als bestand te delen), **de bibliotheek verplaatsen** naar een andere opslag, **uploaden wat RomM mist**, en **save-sync voor losse emulators** (DraStic, melonDS, mGBA, Snes9x EX+ en meer).

### Nieuw in 1.2.0
- **Downloads gaan verder waar ze stopten** (volle opslag, gesloten app, herstart) en **de wachtrij overleeft een herstart**; een **limiet per server** houdt strenge servers tevreden. **Herschik de wachtrij** (▲ ▼, of **Y** om er een vooraan te zetten) en **houd ruimte vrij** zodat downloads stoppen voor de opslag vol is.
- **Verlanglijst op de automatische piloot**, **.m3u-playlists** voor games met meerdere schijven, en een **opslagadvies** als "Alles downloaden" niet past.
- **Covers voor ES-DE** (en de ES-DE-koppeling van Cocoon) na elke download, van libretro-thumbnails. **Cocoon**: voeg Dogmatix+ toe als tegels per console, opgeslagen weergave of Downloads.
- **RetroAchievements-badges**, **profielen met een pincode**, **statistieken** met wat je speelt in ES-DE, en **opgeslagen weergaven** met een snelkoppeling in ES-DE.
- **BIOS-controle** voor zo'n twintig systemen, **IPS / UPS / BPS-patches** vanuit de bestandsverkenner, en **links die je met de app deelt** gaan meteen in de map van een console.
- **RomM-collecties** in beide richtingen, **DAT rechtstreeks van Redump**, een **tweede scherm** voor handhelds met twee schermen en tv's, een wekelijkse **automatische back-up**, en een nieuw icoon.

### Nieuw in 1.1.0
- **Snellere herscans**: bronnen waarvan de lijst niet veranderde, worden overgeslagen (6 bronnen van 4.000 games: van 18 naar 2 seconden). Een bron die faalt, houdt zijn games, en een webbron kan **reserve-adressen** hebben.
- **Scannen op de achtergrond** (dagelijks, via wifi, tijdens het laden, 's nachts) met een melding als er nieuwe games opduiken; nieuwe games krijgen een badge **Nieuw**, een filter en de sortering *Nieuwste eerst*.
- **Collecties**: je eigen lijsten naast de favorieten.
- **Alles wat getoond wordt downloaden**, na een controle van aantal, grootte en vrije ruimte; desgewenst alleen de beste versie van elke game.
- **Nintendo Switch-updates en DLC**: zie welke update of DLC je mist en haal ze op.
- **DAT-controle** met No-Intro / Redump: goede dumps, verkeerde namen (met één tik hernoemd), onbekende bestanden en ontbrekende games.
- **De snelheidslimiet werkt** (dat deed hij eerder nooit) voor alle downloads samen, torrents inbegrepen, desgewenst niet 's nachts.
- De **bestandsverkenner** kan hernoemen, verplaatsen en uitpakken; **deel je bronnen als QR-code**; **installeer updates vanuit de app**; een **widget** op je startscherm; een **dikke focusrand** voor tv en handheld.

### Games vinden
- **Eén lijst** met de games uit al je bronnen.
- **Zoeken** dat fouten vergeeft: accenten, streepjes en dubbele letters maken niet uit, dus "yugioh" vindt *Yu-Gi-Oh!*.
- **Filter** op console, regio, taal en type, en **sorteer** op naam of grootte.
- Een groene **✓** laat zien welke games je al hebt.
- **★ Favorieten**: geef de games die je leuk vindt een ster en toon alleen die.

### Games downloaden
- Werkt met **directe links, torrents en magnetlinks**.
- **Meerdere downloads tegelijk**, met pauzeren, hervatten, opnieuw proberen en een snelheidslimiet.
- **ZIP- en 7z-bestanden worden uitgepakt**, dat doet de app voor je.
- Elke console krijgt **een eigen map**. Mappen die je al hebt (zoals `gba` of `psx`) worden hergebruikt, en je kunt twee mappen die bij dezelfde console horen samenvoegen.
- Je **downloadlijst blijft bewaard** als je de app sluit. Selecteer meerdere downloads om ze samen te stoppen, opnieuw te proberen of te verwijderen.
- Optioneel: **TorBox** en **Real-Debrid** — betaalde diensten die torrents voor je ophalen, zodat de download een gewoon, snel bestand is.
- **Alleen op wifi, alleen tijdens het opladen of alleen 's nachts**: nieuwe downloads wachten tot aan de ingestelde voorwaarden is voldaan en zeggen waarop ze wachten. Een **controlegetal-check** vergelijkt een klaar bestand met de hash die de bron publiceert. In de game-info kiest **Beste versie** de versie die bij jou past (jouw regio en taal, geen demo's). Een voltooide download kun je in een emulator **openen**.

### Je collectie netjes houden *(nieuw in DogmatixPlus)*
- **Dubbele games**: vindt games die meer dan eens op je toestel staan, laat zien hoeveel ruimte je wint en laat je de extra kopie verwijderen. Er wordt niets verwijderd voordat je precies hebt gezien welke bestanden verdwijnen.
- **Bibliotheekoverzicht**: voor elke console hoeveel games er in de lijst staan, hoeveel je er in bezit hebt, hoeveel er op je toestel staan en hoe groot dat is, en wanneer er voor het laatst is gescand.
- **Back-up maken en terugzetten**: sla je instellingen, bronnen, favorieten en downloads op in één bestand en zet ze later terug — handig voor een nieuw toestel.
- **Scanvoortgang**: een percentage en de resterende tijd terwijl je bronnen worden gelezen. Bronnen worden **naast elkaar** gescand, dus dat gaat snel.
- **Game-sets**: vindt schijfkopieën die niet kunnen draaien (een `.cue` waarvan de track weg is, een afspeellijst die een verwijderde schijf noemt) en maakt `.m3u`-afspeellijsten voor games met meerdere schijven.
- **Opslag**: ruimte per console, je grootste games, en of de downloads in de wachtrij nog passen.
- **Verlanglijst**: noteer games die je wilt hebben; je krijgt een melding zodra er een in je bronnen opduikt.
- **Exporteer** je collectie als spreadsheet (CSV) of webpagina. De zoeker naar dubbele games kan ook **voorstellen welke kopie je houdt**.
- **Bestandsverkenner**: kijk in je mappen, zie groottes en welke bestanden als game tellen, controleer schijfsets, open of verwijder bestanden.
- **Scanrapport**: na een scan één overzicht van de bronnen die mislukten en waarom, met *Deze opnieuw scannen*; elke bron toont zijn laatste resultaat.

### Gemaakt voor handhelds
- **Bedien alles met een gamepad**: D-pad, A/B/X/Y en de schouderknoppen. De hints onderaan het scherm passen bij jouw pad (Xbox, Nintendo of PlayStation), en je kunt de knoppen omwisselen als je pad ze andersom doorgeeft.
- **Liggende en staande** indeling, met een filterpaneel naast de lijst op brede schermen.
- **Licht, donker of echt zwart** thema (mooi op OLED-schermen), **twaalf accentkleuren** en **Material You** (Android 12+: de kleuren volgen je achtergrond).
- **◀ ▶ springt door de bibliotheek op beginletter**, handig bij een lange lijst zonder aanraakscherm.

### Werkt met je gamelauncher
- **ES-DE** en **iiSU** krijgen in elke console een vermelding "Search for more games", ingesteld met één knop. **Daijishō** laat je de paar waarden zien die je zelf moet invullen.

### Werkt met RomM
- Stuur voltooide downloads naar je **RomM**-server, of gebruik RomM als bron van games.
- **Saves synchroniseren**: je RomM-server bewaart je **opgeslagen spellen en save states**. Kies de mappen waarin je emulator opslaat (voor RetroArch: `saves` en `states`) en DogmatixPlus stuurt nieuwe voortgang naar de server en haalt nieuwere voortgang — van een andere handheld of van de webspeler van RomM — op. Is een save aan beide kanten gewijzigd, dan kies jij welke je houdt; een kopie die vervangen wordt, blijft 30 dagen bewaard. Het kan vanzelf gebeuren als je de app opent of vanuit een spel terugkomt (*Instellingen → Saves synchroniseren*).
- Games die je RomM-server al heeft, worden in de bibliotheek **gemarkeerd**. Een server thuis met een **zelfgemaakt certificaat** werkt zodra je de vingerafdruk hebt gecontroleerd. Een onderbroken upload **gaat verder** waar hij stopte. De **covers** van RomM kunnen in ES-DE worden opgehaald.
- Save-sync kan elke paar uur **op de achtergrond** draaien en kan ook **verwijderingen overnemen** (standaard uit, met waarborgen). Bij een save die aan beide kanten is gewijzigd zie je nu beide tijden en groottes.

### Jouw bronnen, op jouw manier
- Voeg bronnen met de hand toe, of **importeer en exporteer** ze als bestand om ze tussen toestellen te delen. De export neemt nu ook je **★ favorieten** mee; importeren voegt ze op het andere toestel toe.
- Een korte **startgids** helpt je om je ROM-map te kiezen en je bronnen te importeren.

### Talen
- **Engels, Spaans, Nederlands, Frans, Duits, Italiaans en Portugees** (*Instellingen → Taal*, of volg de taal van je telefoon).

---

## Installeren

1. Open de **[Releases-pagina](https://github.com/Tufein/DogmatixPlus/releases)** op je telefoon of handheld, of download het bestand daar en kopieer het naar je toestel.
2. Download een van de twee bestanden:
   - **`DogmatixPlus-release.apk`** — *de gewone keuze.* De app heet **Dogmatix+** en heeft een eigen pakketnaam, dus hij wordt **naast** de officiële Dogmatix en oudere DogmatixPlus-versies geïnstalleerd; er wordt niets aangeraakt van wat je al hebt.
   - **`DogmatixPlus-debug.apk`** — een debugversie die ook naast al het andere komt.
   - **Kom je van een oudere DogmatixPlus?** Gebruik in de oude app *Instellingen → Back-up maken* en daarna *Instellingen → Back-up terugzetten* in Dogmatix+, en voer de instelling voor ES-DE / iiSU / Daijishō nog één keer uit.
3. Open het bestand en sta **"Onbekende apps installeren"** toe als Android erom vraagt.

Je hebt **Android 10 of nieuwer** nodig. De app staat niet in Google Play.

## Eerste start

1. De welkomstgids legt de basis uit.
2. **Kies je ROM-map** — de map waar je games in moeten komen.
3. **Importeer je bronnen** (een bestand met je gamelijsten) — of sla dit over en voeg later bronnen toe in het tabblad **Bronnen**.
4. Open de **Bibliotheek**, zoek een game en tik erop (of druk op **A**) om de gamepagina te openen. Kies daar **Downloaden** of **Spelen**.

## Een gamepad gebruiken

| Knop | Wat doet hij? |
|---|---|
| D-pad | Navigeren |
| **A** | Kiezen / gamepagina openen; daar downloaden of spelen |
| **B** | Een stap terug |
| **X** | Game-info |
| **Y** | Zoeken |
| **Select** | Een game een ster geven of de ster weghalen |
| **LB / RB** | Wisselen tussen de filters en de lijst |
| **ZL / ZR** | Vorige / volgende sectie |
| **R3** | Het filterpaneel in- of uitklappen |

Alles werkt ook met het touchscreen. De hints verschijnen alleen als er een controller is aangesloten.

## Goed om te weten

- Verwijderde games gaan naar de prullenbak. Herstel of leeg definitief via Hulpmiddelen → Prullenbak en herstel. Ruimte komt vrij na het legen; de gewone bestandsverkenner verwijdert nog definitief.
- **Een back-upbestand bevat je accountsleutels** (TorBox, Real-Debrid, RomM). Houd het privé.
- **Het venster met game-info blijft leeg in de versies van de app die je hier downloadt**, omdat het een gratis sleutel van een gamedatabase nodig heeft die wordt toegevoegd wanneer de app wordt gebouwd.
- DogmatixPlus zoekt zelf niet naar games. Het leest alleen de bronnen die **jij** toevoegt.
- **Alle publieke releases zijn reguliere releases.** Versies volgen 1.0.0, 1.1.0, …, 1.9.0, 2.0.0. Zie de [releasenummering](docs/releases/numbering.md).
- **Werkt iets niet?** *Instellingen → Diagnose delen* maakt een tekstrapport voor een foutmelding; tokens, serveradressen en magnetlinks worden eerst verwijderd.

---

## Credits

DogmatixPlus is een kleine laag bovenop twee andere projecten. Het meeste van wat je elke dag gebruikt, is van hen.

| Project | Gemaakt door | Wat het bracht |
|---|---|---|
| **[Milou](https://github.com/santiifm/milou)** | [santiifm](https://github.com/santiifm) | De oorspronkelijke app en de hele motor ervan: bronnen lezen, games sorteren op console / regio / taal, zoeken, downloaden en uitpakken. |
| **[Dogmatix](https://github.com/cortinadev/dogmatix)** | [Rafa Cortina](https://github.com/cortinadev) | De handheldversie: bediening met een gamepad, liggende indeling, thema's, favorieten, pauzeren en hervatten, de startgids, ES-DE / iiSU / Daijishō, TorBox en Real-Debrid, RomM. |
| **DogmatixPlus** | [Tufein](https://github.com/Tufein) | Zoeken naar dubbele games, bibliotheekoverzicht, back-up maken en terugzetten, scanvoortgang, saves synchroniseren met RomM, game-setcontrole, opslagoverzicht, verlanglijst, export, downloadplanning, controlegetal-checks, de Nederlandse, Franse, Duitse, Italiaanse en Portugese vertalingen, en deze releases. |

DogmatixPlus is **gemaakt met hulp van AI**: de code, de tests en de documentatie zijn samen met een AI-assistent geschreven en in meerdere controlerondes nagekeken. De beslissingen, de richting en het publiceren liggen bij de beheerder.

## Disclaimer

Deze app is alleen bedoeld voor educatieve doeleinden. Je bent er zelf verantwoordelijk voor dat je het wettelijke recht hebt om welke content dan ook te downloaden.

Milou en Dogmatix hebben geen licentie, dus alle rechten op hun code blijven bij hun auteurs. DogmatixPlus is een onofficiële persoonlijke aanpassing en is aan geen van beide projecten gelieerd. Ben je een van de oorspronkelijke auteurs en wil je dat er iets wordt aangepast of verwijderd? Open dan een issue.

---

## Voor ontwikkelaars

De technische details — hoe de app is gebouwd, hoe de functie Dubbele games beslist, de mappenstructuur, deep links, de gebruikte technologie en meer — staan in **[TECHNICAL.md](TECHNICAL.md)**. Zie ook [FRONTENDS.md](FRONTENDS.md) voor het instellen van de launchers en [CHANGELOG.md](CHANGELOG.md) voor elke wijziging. Het bestand TECHNICAL.md is in het Engels.
