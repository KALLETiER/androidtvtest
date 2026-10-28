# Native Update-Prüfung

`de.andre.heimspiel.UpdateInstrumentation` ist ein eigenständiger Android-
Instrumentation-Runner ohne JUnit-Abhängigkeit. Er prüft den echten Android-
Paketmanager sowie die privaten Parser-/Verifikationsmethoden per Reflection;
die Produktions-App enthält dafür keine Test-Schnittstellen.

Nur auf einem isolierten Emulator ausführen. Die Prüfung lädt nichts herunter
und installiert kein Paket. Sie startet die Hauptaktivität, prüft vorbereitete
APK-Dateien und legt eine verifizierte Kopie im privaten Update-Cache ab.

Benötigt werden die normale Debug-APK, ihre Android-Test-APK und vier Fixtures
unter `cache/update-qa` im privaten Verzeichnis der Ziel-App:

- `newerApk`: gleiche Paket-ID, gleicher Signierer, höherer Versionscode.
- `oldApk`: ältere Version der Ziel-App.
- `wrongSignerApk`: gleiche Paket-ID, höherer Versionscode, anderer Signierer.
- `wrongPackageApk`: andere Paket-ID.

Die bereits installierte APK liefert der Runner selbst als zusätzliche Fixture
für die Ablehnung einer identischen Version. `expectedNewVersionCode` ist optional
und entspricht standardmäßig dem installierten Versionscode plus eins.

Beispiel für den während der Entwicklung verwendeten API-30-Emulator:

```powershell
adb -s emulator-5560 shell am force-stop de.andre.heimspiel
adb -s emulator-5560 shell appops set de.andre.heimspiel REQUEST_INSTALL_PACKAGES deny
adb -s emulator-5560 shell am instrument -w -r `
  -e newerApk /data/data/de.andre.heimspiel/cache/update-qa/newer.apk `
  -e oldApk /data/data/de.andre.heimspiel/cache/update-qa/old.apk `
  -e wrongSignerApk /data/data/de.andre.heimspiel/cache/update-qa/wrong-signer.apk `
  -e wrongPackageApk /data/data/de.andre.heimspiel/cache/update-qa/wrong-package.apk `
  -e expectedNewVersionCode 6 `
  -e testPermissionGate true `
  de.andre.heimspiel.test/de.andre.heimspiel.UpdateInstrumentation
```

Die optionale Prüfung `testPermissionGate=true` setzt eine verweigerte
Installationsfreigabe voraus. Der Runner verweigert diesen Schritt ausdrücklich,
wenn `canRequestPackageInstalls()` bereits wahr ist. Bei fehlender Freigabe muss
ein Aufruf von `install()` im Status `needs_permission` enden; es wird kein
Installer geöffnet. Ohne dieses Argument wird der Schritt als `skipped` gemeldet.

Das Ergebnis enthält `passed`, `checks`, `errors`, `checkCount` und `skippedCount`.
Für die vollständige Prüfung werden `passed=true`, `errors=[]` und
`skippedCount=0` erwartet. Ein Fehlschlag nennt den betroffenen Schritt und dessen
Fehler. Die Prüfung deckt keine Netzwerkübertragung oder bestätigte Installation
durch den Systemdialog ab.
