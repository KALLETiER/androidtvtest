# Die fünf Heimspiel-Vorlagen

Alle fünf sind in Andrés Heimspiel **0.5.0** enthalten. Unter **Einstellungen →
Design** die gewünschte Karte wählen. Die Vorlage wird sofort angewendet und
für den nächsten App-Start gespeichert. Mit **Startbildschirm ansehen** lässt
sich die Gestaltung ohne erneuten Startklang betrachten.

| Vorlage | Gestaltung | Quelldatei |
| --- | --- | --- |
| Dunkles Bayern | Anthrazit, dezente Arena, große Apps, Spielplan unten | `premium.css` |
| Rot-Weiß | Sportmagazin, kräftiges Rot, weiße Karten, Spielplan oben rechts | `template-redwhite.css` |
| Apple-TV-Stil | Perlgrau/Weiß, weiche Karten, mittige Navigation, rote Akzente | `template-apple.css` |
| Bayern Lounge | Große Uhr, ruhige rote Arena, lokale Markenlogos, große HDMI-Karten | `template-lounge.css` |
| Bayern Rotglut | Dunkelroter Hintergrund, warme weiße Schrift, Arena rechts, helle HDMI-Karten | `template-rotglut.css` |

Rotglut übernimmt die großzügige Anordnung der Lounge und setzt sie in Rot und
Elfenbein um. Rot-Weiß behält seine eigene Sportmagazin-Anordnung. In Lounge
und Rotglut stehen die App-Namen unter einheitlich großen Markenflächen;
Gerätekarten verwenden die tatsächlichen HDMI-IDs und Statusangaben. Eine
kompakte Spielkarte ersetzt dort die große Spielplanreihe. Sie nutzt den fest
gekennzeichneten Datenstand 28.10.2026, keine Live-Anbindung.

Die Dateien liegen in `app/src/main/assets`. `templates.css` gestaltet die
Vorlagenauswahl; `updates.css` den gemeinsamen Update-Bereich. Die Vorlagen
verwenden dieselbe Oberfläche und sind keine eigenständigen Websites. Jede
Vorlage unterstützt die Einstellungen und den Update-Bereich.

Im Vorlagenpaket liegen die installierbare APK, fünf vollständige Vorschauen,
der Vergleich und die zugehörigen CSS-Dateien. Der vollständige App-Quellcode
ist separat als Quellcode-ZIP verfügbar. Die Vorschauen verwenden Beispieldaten;
die TV-App zeigt die auf dem Gerät vorhandenen Apps und Eingänge.

Beim Wechsel werden Kachelecken zunächst passend zur Vorlage gesetzt. Eigene
Favoriten, Eingangsnamen, Begrüßung und Audio bleiben erhalten. Die Auswahl
bleibt auch bei einem passenden APK-Update über die bestehende Installation
gespeichert.