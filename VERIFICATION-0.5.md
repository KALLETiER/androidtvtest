# Prüfung von Version 0.5.0

Stand: 28.10.2026. Paket `de.andre.heimspiel`, Versionscode 5.

- `assembleDebug`, `assembleDebugAndroidTest` und `lintDebug` erfolgreich.
- APK-v2-Signatur gültig; derselbe Signierer wie Version 0.4.0:
  `65cb0507c5e48ec2e4b564033e78c2bd9a8728f5ae91a774bb7c71fac510113d`.
- Fünf Vorlagen einschließlich Bayern Rotglut bei 1920×1080 und 1280×720
  geprüft. Home passt ohne Scrollen, Footer sichtbar, D-pad und gespeicherte
  Vorlagenwahl funktionieren. Startseiten und Einstellungen visuell geprüft.
- Update-Oberfläche geprüft: getrennte Aktionen für Suchen, Herunterladen,
  Datei-Auswahl und Installieren; Fortschritt, Fehlerstatus und URL-Eingabe
  per Fernbedienung. Keine automatische Installation durch Suche/Download.
- Bisherige UI-Prüfung, Lounge- und Update-Prüfung erfolgreich; keine
  JavaScript-Laufzeitfehler, externen Browserabrufe oder defekten Bilder.
- Android-11-Emulator, API 30: 14 native Prüfungen erfolgreich. Darunter
  HTTP-Quelle/ungültige Manifestdaten, fehlende Prüfsumme, alte/gleiche Version,
  falsches Paket, falscher Signierer, akzeptierte neuere passende APK,
  Bereitstellung ohne Installation und verweigerte Installationsfreigabe.
- Der Updater lädt nur per HTTPS, begrenzt Manifest/APK-Größe und prüft beim
  Download zusätzlich SHA256 und die Version aus dem Manifest. Lokale APKs
  werden ebenfalls auf Paket, neuere Version und aktuellen Signierer geprüft.
- Der sichtbare Android-Installationsdialog mit aktiviertem Installieren-Button
  wurde auf API 30 erreicht; keine Installation bestätigt. Separater Prüflauf
  mit erlaubter Installation ebenfalls erfolgreich, 14 Prüfungen ohne Fehler.
- Auf dem Xiaomi Fire TV ist `com.android.documentsui/.picker.PickActivity`
  für die APK-Dateiauswahl vorhanden (nur Auflösung geprüft, nicht geöffnet).
- Quellcode und APK im GitHub-Repository hochgeladen. Auf ausdrücklichen Wunsch
  bleibt es privat. Die finale App startet ohne Onlinequelle; APK-Dateien sind
  der Standardweg. Ein GitHub-Login vom PC wird nicht in die App kopiert.
- WLAN-Update auf dem freigegebenen Xiaomi Fire TV 192.168.0.77 erfolgreich:
  `versionName=0.5.0`, `versionCode=5`. Einstellungsdatei vor/nach dem Update
  identisch. Fernseher blieb `Asleep`, Display `OFF`; App nicht geöffnet.

APK-SHA256: `a527c01cf464a394f05d40fe31fed9ba9265d2cc409ad566edccb7831e7d8100`.

Der Spielplan bleibt ein fester Stand vom 28.10.2026. HDMI-Bildwechsel und
Home-Tasten-Übernahme wurden nicht verändert. Die App deaktiviert keinen
Amazon-Launcher. Die Vorschauen verwenden Beispiel-Apps und Beispiel-Eingänge.
