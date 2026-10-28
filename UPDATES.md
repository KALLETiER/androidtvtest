# APK-Updates ab Version 0.5

Andrés Heimspiel 0.5.0 enthält einen nativen Update-Bereich unter
**Einstellungen → Updates**. Die Oberfläche nutzt dafür die Android-Brücke;
WebView-Inhalte laden und installieren keine APK selbst. Das GitHub-Repository
bleibt auf Wunsch privat. Standardmäßig ist deshalb keine Onlinequelle aktiv;
Updates erfolgen über ausgewählte APK-Dateien oder per WLAN/ADB.

## Eine neue Version am TV installieren

### Über die Dateiauswahl

1. Die neue APK aus dem privaten GitHub-Repository mit deinem GitHub-Zugang
   herunterladen. Die neuere, passend signierte Heimspiel-APK auf einem Speicher beziehungsweise
   bei einem Dateianbieter bereitstellen, den die TV-Dateiauswahl erreichen kann.
2. **APK-Datei auswählen** öffnen und die Datei wählen. Der Import läuft über
   Androids Dokumentauswahl und kopiert die Datei in den privaten App-Cache.
3. Bei Bedarf **Installationsfreigabe öffnen** wählen und die Installation
   unbekannter Apps für **Andrés Heimspiel** erlauben. Das entspricht der
   Android-Freigabe zu `REQUEST_INSTALL_PACKAGES`; Fire OS kann das Menü anders
   benennen.
4. Nach erfolgreicher Prüfung **Update installieren** wählen und im
   Systemdialog des Fernsehers bestätigen.

Ist auf dem TV keine passende Dokumentauswahl oder kein Installationsdialog
verfügbar, bleibt die Installation über WLAN mit `adb install -r` möglich.
Die Update-Funktion verschafft der App keine unbeaufsichtigte
Installationsberechtigung.

### Über eine Onlinequelle

1. Die HTTPS-Adresse der `update.json` unter **Adresse für Online-Updates**
   eintragen und **Quelle speichern** wählen. Die App hat standardmäßig keine
   Onlinequelle hinterlegt.
2. **Nach Updates suchen** wählen. Die App zeigt eine verfügbare neuere Version
   und deren Hinweise an.
3. **Herunterladen** wählen. Fortschritt und Dateiprüfung werden angezeigt.
4. Wie beim Dateiweg die Installationsfreigabe erteilen und die Installation
   im Systemdialog bestätigen.

Prüfung und Download werden jeweils vom Nutzer gestartet. Das Leeren und
Speichern der Quellenadresse deaktiviert Onlineprüfungen; der Dateiweg bleibt
verfügbar. Einstellungen werden bei einem Update über die vorhandene App
erhalten. Eine Deinstallation löscht sie.

## Format der Onlinequelle

Die Quelle liefert ein UTF-8-JSON-Objekt mit diesen Feldern:

| Feld | Inhalt |
| --- | --- |
| `versionCode` | Positive ganze Zahl, höher als der installierte APK-Versionscode |
| `versionName` | Versionsbezeichnung, exakt wie in der APK |
| `apkUrl` | Direkte HTTPS-Adresse der fertig signierten APK |
| `sha256` | SHA256 der APK-Datei als 64 Hexadezimalzeichen |
| `notes` | Optionaler Text mit Änderungen der neuen Version |

Schematisches Beispiel; Adresse und Prüfsumme müssen durch die echten
Release-Daten ersetzt werden:

```json
{
  "versionCode": 6,
  "versionName": "0.6.0",
  "apkUrl": "https://downloads.example.org/heimspiel/Andres-Heimspiel-0.6.0.apk",
  "sha256": "<SHA256-DER-FERTIG-SIGNIERTEN-APK>",
  "notes": "Beschreibung der Änderungen."
}
```

Die JSON-Datei und die APK müssen ohne Anmeldung, Cookies oder zusätzliche
HTTP-Header erreichbar sein. Die App unterstützt HTTPS-Weiterleitungen, aber
keinen Wechsel auf HTTP, keine Zugangsdaten in der URL und keine Anmeldung.
Die Grenzen sind 64 KiB für die Updatebeschreibung und 64 MiB für eine APK.

## Was geprüft wird

- Beide Wege: lesbare APK, Paketname `de.andre.heimspiel`, höherer Versionscode
  und dieselben Signaturzertifikate wie bei der installierten App.
- Online zusätzlich: SHA256 der heruntergeladenen Datei sowie Übereinstimmung
  von Versionscode und Versionsname mit der `update.json`.
- Vor dem Öffnen des Installers wird die zwischengespeicherte APK erneut
  geprüft. Android führt anschließend seine eigene Installationsprüfung aus.

Eine fremde App, ältere oder gleich alte Version und ein Build mit anderem
Schlüssel werden abgelehnt. Beim manuellen Import existiert keine externe
Updatebeschreibung, mit der eine SHA256 verglichen werden könnte.

## Privates GitHub-Repository und vorbereiteter Kanal

Das vorgesehene private Quellcode-Repository ist
[KALLETiER/androidtvtest](https://github.com/KALLETiER/androidtvtest). Die
vorbereiteten Adressen lauten:

- Updatebeschreibung: `https://raw.githubusercontent.com/KALLETiER/androidtvtest/main/update.json`
- APK 0.5.0: `https://raw.githubusercontent.com/KALLETiER/androidtvtest/main/releases/Andres-Heimspiel-0.5.0.apk`

Quellcode, APK und `update.json` sind im privaten Repository hochgeladen.
Die Onlinefunktion bleibt ohne hinterlegte Quelle ausgeschaltet.
Ein privates GitHub-Repository macht Dateien und Release-Downloads nicht anonym
erreichbar. Deshalb funktioniert dessen normale GitHub- oder Raw-Adresse
nicht für diesen Updater, solange das Repository privat ist. Eine Anmeldung
mit GitHub-Token ist in der App derzeit nicht implementiert.

Der Quellcode kann privat bleiben, während ausschließlich die signierte APK
und die `update.json` über einen passenden öffentlichen HTTPS-Downloadkanal
bereitgestellt werden. Alternativ die APK aus dem privaten Repository separat
übertragen und den Dateiweg oder ADB verwenden. Wird der vorbereitete Kanal
später ohne Anmeldung erreichbar, kann seine Adresse am TV eingetragen werden.
Veröffentlichung und Zugriffsberechtigungen des Downloadkanals müssen zur
gewünschten Sichtbarkeit passen. Keine Tokens, Schlüssel oder Passwörter in
Dateien oder URLs ablegen.

## Eine Folgeversion veröffentlichen

1. **Mit demselben lokalen Signierschlüssel bauen.** Die bisherigen APKs nutzen
   die lokale Android-Entwicklungssignatur. Ein frischer Debug-Schlüssel auf
   einem anderen PC oder CI-Runner ist kein kompatibles Update. Den vorhandenen
   Schlüssel außerhalb des Repositorys sichern und für Folgeversionen
   beibehalten. Ein Wechsel zu einer Release-Signatur braucht einen gesonderten
   Migrationsplan und darf bestehende Installationen nicht unbemerkt ersetzen.
2. **Version erhöhen und prüfen.** Beispiel für die nächste Version:

   ```powershell
   .\gradlew.bat assembleDebug lintDebug '-PheimspielVersionCode=6' '-PheimspielVersionName=0.6.0'
   ```

   Die APK liegt unter `app/build/outputs/apk/debug/app-debug.apk`. Paket,
   Versionswerte und Signatur mit den Android-Build-Tools überprüfen. Erst nach
   dem endgültigen Signieren die APK benennen und ihre Prüfsumme ermitteln.
3. **APK bereitstellen.** Die fertig geprüfte APK unter der endgültigen,
   ohne Anmeldung erreichbaren HTTPS-Adresse veröffentlichen. Eine Datei unter
   einer Versionsadresse danach nicht mehr austauschen.
4. **Manifest erzeugen.** Der Generator berechnet die SHA256 der angegebenen
   APK. Versionsangaben müssen zu den tatsächlich darin enthaltenen Werten
   passen. Beispiel nach dem Kopieren der endgültigen APK nach `dist`:

   ```powershell
   python tools/create-update-manifest.py --apk dist/Andres-Heimspiel-0.6.0.apk --apk-url https://downloads.example.org/heimspiel/Andres-Heimspiel-0.6.0.apk --version-code 6 --version-name 0.6.0 --output dist/update.json --notes "Beschreibung der Änderungen."
   ```

   Die vollständige Hilfe zeigt `python tools/create-update-manifest.py --help`.
5. **Updatebeschreibung zuletzt veröffentlichen.** `update.json` darf erst auf
   die neue APK verweisen, wenn deren Download funktioniert. JSON und APK ohne
   angemeldete GitHub-Sitzung auf Erreichbarkeit prüfen.
6. **Auf einer bestehenden Installation prüfen.** Von der vorherigen Version
   aus suchen, herunterladen, Systemdialog bestätigen und anschließend
   installierte Version sowie erhaltene Vorlage/Favoriten/Einstellungen
   kontrollieren. Quellcode- und UI-Prüfungen ersetzen diesen Gerätetest nicht.

`local.properties`, Keystores, Build-Caches und Zugangsdaten gehören nicht in
GitHub oder in das Quellcode-ZIP. Die `.gitignore` schließt lokale
Schlüsseldateien aus. Ein automatischer CI-Build muss einen kompatiblen
Signierschlüssel gesondert erhalten; das Repository selbst enthält ihn nicht.
