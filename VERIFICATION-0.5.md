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
- Das private GitHub-Repository ist als Update-Kanal vorbereitet. Anonyme
  Online-Downloads benötigen noch eine öffentlich erreichbare Quelle; ein
  GitHub-Login vom PC wird nicht in die App kopiert.

APK-SHA256: `a8b453d616adb3575592f8d40625bb28e388d30d6e738a37df9c9d2f3dd89369`.

Der Spielplan bleibt ein fester Stand vom 28.10.2026. HDMI-Bildwechsel und
Home-Tasten-Übernahme wurden nicht verändert. Die App deaktiviert keinen
Amazon-Launcher. Die Vorschauen verwenden Beispiel-Apps und Beispiel-Eingänge.
