# Produktion auf dem Raspberry Pi

Dieses Dokument beschreibt Deployment, SQLite und Bootstrap-Admin fuer die Spring-Boot-App.

Alle lokalen Befehle werden vom Repository-Stamm ausgefuehrt. Einen Ueberblick bietet
die [README](../../README.md); die private Mod-Recherche-API und deren Backup-Verfahren
stehen im [Katalog-Betriebshandbuch](../mod-catalogue/operations.md).

## 1. Voraussetzungen auf dem PC

- Java 17 ist installiert.
- PowerShell ist verfuegbar.
- `scp` und `ssh` sind verfuegbar, zum Beispiel ueber OpenSSH fuer Windows.
- SSH-Zugriff auf den Raspberry Pi funktioniert.
- Du fuehrst die PowerShell-Befehle im lokalen Projektordner aus, also im Ordner mit `gradlew.bat` und `deploy-to-pi.ps1`.

Pruefen:

```powershell
java -version
scp -V
ssh -V
```

## 2. Lokale Entwicklungsumgebung

In der lokalen Entwicklungsumgebung wird beim ersten Start automatisch ein Admin erstellt, wenn noch kein Admin in der lokalen SQLite-Datenbank existiert.

Standard-Zugangsdaten:

```text
Benutzername: admin
Passwort: admin123
```

Die lokale SQLite-Datei liegt hier:

```text
./data/webserver.sqlite
```

Lokal neu anfangen:

```powershell
# App vorher stoppen
Remove-Item .\data\webserver.sqlite
.\gradlew.bat bootRun
```

Danach wird wieder ein frischer lokaler Admin mit `admin` / `admin123` erstellt.

## 3. Lokale Deploy-Konfiguration

`deploy-to-pi.ps1` erwartet eine lokale Datei:

```text
deploy-to-pi.local.ps1
```

Diese Datei wird nicht committed. Erstelle sie aus dem Beispiel:

```powershell
Copy-Item .\deploy-to-pi.local.example.ps1 .\deploy-to-pi.local.ps1
```

Beispiel:

```powershell
$PiHost = "raspberrypi.local"
$PiUser = "pi"
$TargetPath = "/opt/heimserver/app.jar"
$SshKey = "$env:USERPROFILE\.ssh\id_rsa"
$DisplayName = "Webserver Display Name"
$BootstrapAdminUsername = "admin"
$BootstrapAdminPassword = "dein-sehr-sicheres-passwort"
$ServiceName = "webserver"
$InstallSystemdService = $true
$RestartSystemdService = $true
```

`TargetPath` ist der Zielpfad der JAR-Datei auf dem Raspberry Pi.

`BootstrapAdminUsername` und `BootstrapAdminPassword` werden beim Deployment automatisch in eine Environment-Datei auf dem Pi geschrieben:

```text
/opt/heimserver/webserver.env
```

Der systemd-Service wird durch das Deploy-Script automatisch so erstellt oder aktualisiert, dass er diese Datei mit `EnvironmentFile=.../webserver.env` laedt.
`DisplayName` wird ebenfalls in diese Datei geschrieben und steuert den angezeigten Namen auf der Hauptseite.
`DisplayName` ist Pflicht. Wenn der Wert in `deploy-to-pi.local.ps1` fehlt, bricht `deploy-to-pi.ps1` ab.

Wenn dein bestehender Service anders heisst:

```powershell
$ServiceName = "heimserver"
```

## 4. Vorbereitung auf dem Raspberry Pi

Normalerweise erstellt `deploy-to-pi.ps1` den Zielordner automatisch. Fuer Logs und Besitzrechte ist diese einmalige Vorbereitung trotzdem sinnvoll:

```bash
sudo mkdir -p /opt/heimserver/data
sudo mkdir -p /var/log/heimserver
sudo chown -R $USER:$USER /opt/heimserver
sudo chown -R $USER:$USER /var/log/heimserver
```

Die SQLite-Datenbank wird automatisch erzeugt unter:

```text
/opt/heimserver/data/webserver.sqlite
```

Das gilt, wenn die App mit `WorkingDirectory=/opt/heimserver` gestartet wird.

## 5. Deployment vom PC

Auf dem PC:

```powershell
.\deploy-to-pi.ps1
```

Das Script macht:

1. `.\gradlew.bat clean bootJar`
2. sucht das Spring-Boot-JAR unter `build/libs`
3. erstellt den Zielordner auf dem Pi, falls er fehlt
4. stoppt den systemd-Service, falls `$RestartSystemdService = $true`
5. laedt das JAR per `scp` als temporäre `.uploading`-Datei auf den Pi
6. ersetzt das finale JAR per `mv`
7. schreibt `webserver.env` neben das JAR auf dem Pi, ebenfalls erst als temporäre Datei
8. setzt fuer `webserver.env` die Rechte auf `600`
9. erstellt oder aktualisiert `/etc/systemd/system/$ServiceName.service`
10. setzt im Service automatisch `EnvironmentFile=.../webserver.env`
11. aktiviert den Service
12. startet den Service neu

Das Stoppen vor dem Upload ist wichtig. Die laufende JVM darf nicht aus einem JAR lesen, das gerade per `scp` überschrieben wird.

Damit kommen `BOOTSTRAP_ADMIN_USERNAME` und `BOOTSTRAP_ADMIN_PASSWORD` aus `deploy-to-pi.local.ps1` automatisch beim Java-Prozess an.

## 6. Erster Produktionsstart

Wenn noch kein Admin in SQLite existiert, erstellt die App beim Start den Bootstrap-Admin aus:

```text
BOOTSTRAP_ADMIN_USERNAME
BOOTSTRAP_ADMIN_PASSWORD
```

Diese Werte kommen durch `deploy-to-pi.ps1` aus `deploy-to-pi.local.ps1`.
Der sichtbare Seitenname kommt aus `APP_DISPLAY_NAME`, ebenfalls aus `deploy-to-pi.local.ps1`.

Beim ersten Start passiert automatisch:

1. Ordner `data/` wird erstellt, falls er fehlt.
2. SQLite-Datei `data/webserver.sqlite` wird erstellt, falls sie fehlt.
3. Tabelle `users` wird erstellt, falls sie fehlt.
4. Der Bootstrap-Admin wird angelegt, falls noch kein Admin existiert.

Wichtig: Die Bootstrap-Werte wirken nur, solange noch kein Admin in der Datenbank existiert. Spaetere Deployments ueberschreiben keinen bestehenden Admin.

## 7. Systemd-Service

Normalerweise erstellt `deploy-to-pi.ps1` die Service-Datei automatisch.

Bei diesem Beispiel:

```powershell
$TargetPath = "/opt/heimserver/app.jar"
$ServiceName = "webserver"
```

erzeugt das Script diesen Service:

```ini
[Unit]
Description=My Webserver
After=network.target

[Service]
WorkingDirectory=/opt/heimserver
EnvironmentFile=/opt/heimserver/webserver.env
ExecStart=/usr/bin/java -jar /opt/heimserver/app.jar --spring.profiles.active=prod
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Status pruefen:

```bash
sudo systemctl status webserver
```

Logs pruefen:

```bash
journalctl -u webserver -f
tail -f /var/log/heimserver/heimserver.log
```

## 8. Fehlerfall: Bootstrap-Passwort fehlt

Wenn im Log steht:

```text
BOOTSTRAP_ADMIN_PASSWORD must be set before the first production start
```

dann kommt `BOOTSTRAP_ADMIN_PASSWORD` nicht beim Java-Prozess an.

Auf dem Pi pruefen:

```bash
sudo systemctl cat webserver
ls -l /opt/heimserver/webserver.env
cat /opt/heimserver/webserver.env
```

Die Datei muss enthalten:

```text
APP_DISPLAY_NAME="dein-name"
BOOTSTRAP_ADMIN_USERNAME="admin"
BOOTSTRAP_ADMIN_PASSWORD="dein-sehr-sicheres-passwort"
```

Wenn die Webseite trotzdem `Webserver` zeigt, bekommt der Java-Prozess `APP_DISPLAY_NAME` nicht aus systemd. Dann erneut deployen und in der Ausgabe auf diese Zeile achten:

```text
Remote environment file:
APP_DISPLAY_NAME="..."
```

Im Service muss stehen:

```ini
EnvironmentFile=/opt/heimserver/webserver.env
```

Normalerweise reicht danach ein erneutes Deployment vom PC:

```powershell
.\deploy-to-pi.ps1
```

## 9. Login- und Registrierungsablauf

- Admin-Login: `/login`
- Registrierung neuer Benutzer: `/register`
- Admin-Dashboard: `/admin/dashboard`
- User-Dashboard: `/user/dashboard`

Ablauf:

1. Benutzer registriert sich ueber `/register`.
2. Der Benutzer ist zuerst nicht freigegeben.
3. Admin loggt sich ein.
4. Admin oeffnet `/admin/dashboard`.
5. Admin bestaetigt den Benutzer.
6. Erst danach kann sich der Benutzer einloggen.

## 10. Neue Produktionsdatenbank erzeugen

Nur machen, wenn alle Benutzer geloescht werden sollen.

```bash
sudo systemctl stop webserver
rm /opt/heimserver/data/webserver.sqlite
sudo systemctl start webserver
```

Beim Neustart wird der Bootstrap-Admin erneut aus `/opt/heimserver/webserver.env` erstellt.

## 11. Hinweise

- `application-prod.properties` bindet die App aktuell an `127.0.0.1`.
- Das ist passend, wenn davor ein Reverse Proxy wie Nginx laeuft.
- Wenn die App direkt im Netzwerk erreichbar sein soll, muss `server.address=127.0.0.1` entfernt oder angepasst werden.
- Die SQLite-Datei sollte nicht deployed oder committed werden.
