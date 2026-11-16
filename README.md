# x15u-camera

Kamera-App-Projekt für das Xiaomi 15 Ultra. GitHub baut bei jedem Push automatisch eine installierbare APK.

## Stand

**Schritt 1: Kamera-Diagnose** (Modul `probe`). Die App liest aus, was das Gerät einer Dritt-App über Camera2 anbietet: sichtbare und versteckte Kameras, RAW-Größen, Hochauflösungsmodi, abschaltbare Verarbeitungsstufen, Videoformate, Hersteller-Schlüssel. Sie braucht keine Berechtigung und nimmt nichts auf.

Die eigentliche Kamera-App (manuelle Steuerung, Master-RAW, Looks) folgt als eigenes Modul, zugeschnitten auf das Diagnose-Ergebnis.

## APK holen

1. Im Repo auf **Actions** warten, bis der Lauf „APK bauen" grün ist (ca. 3 Minuten).
2. Auf dem Handy **Releases → Aktueller Build** öffnen und `kamera-diagnose.apk` laden.
3. Installieren (Installation aus dieser Quelle einmalig erlauben).
4. App öffnen, **Als Datei teilen** antippen, die Textdatei weitergeben.

## Selbst bauen

JDK 17 und Android SDK 35, dann `./gradlew :probe:assembleDebug`.

`debug.keystore` ist ein reiner Test-Schlüssel (Passwort `android`), damit alle Builds gleich signiert sind. Nicht für eine Veröffentlichung im Play Store verwenden.
