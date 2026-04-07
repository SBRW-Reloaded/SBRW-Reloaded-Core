@echo off
setlocal enabledelayedexpansion
echo ========================================
echo Build SBRW-Reloaded-Core pour Linux
echo ========================================
echo.

REM Configurer JAVA_HOME (JDK 11 recommande pour Thorntail 2.6.0)
set "JAVA_HOME=C:\Tools\jdk-11.0.30+7"

echo JAVA_HOME: %JAVA_HOME%
echo Note: Thorntail 2.6.0 recommande JDK 11 pour une compatibilite optimale
echo.

REM Verifier Java
where java >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ERREUR: Java n'est pas installe ou pas dans le PATH
    pause
    exit /b 1
)

java -version
echo.

REM Configurer Maven
set "MAVEN_HOME=C:\Tools\apache-maven"
set "PATH=%MAVEN_HOME%\bin;%PATH%"

where mvn >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ERREUR: Maven n'est pas trouve
    echo Installez Maven ou utilisez build.bat pour l'installation automatique
    pause
    exit /b 1
)

echo Maven trouve
echo.

REM Nettoyer le dossier de distribution precedent
if exist "dist-linux" (
    echo Nettoyage du dossier dist-linux...
    rmdir /s /q "dist-linux"
)

echo ========================================
echo Compilation du projet...
echo ========================================
echo.

REM Build avec Maven
call mvn clean package -DskipTests -e "-Dnfs.core.stage=production"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ========================================
    echo BUILD ECHOUE !
    echo ========================================
    pause
    exit /b 1
)

echo.
echo ========================================
echo Preparation du package Linux...
echo ========================================
echo.

REM Patch Jandex 2.1.2 -^> 2.4.4 dans le fat JAR (fix ArrayIndexOutOfBoundsException)
set "JANDEX_SRC=target\jandex-fix\m2repo\org\jboss\jandex\2.1.2.Final\jandex-2.1.2.Final.jar"
set "THORNTAIL_JAR=target\core-thorntail.jar"
if exist "%JANDEX_SRC%" if exist "%THORNTAIL_JAR%" (
    echo Patch Jandex 2.1.2 -^> 2.4.4 dans le fat JAR...
    powershell -NoProfile -ExecutionPolicy Bypass -File "patch-jandex.ps1" "%THORNTAIL_JAR%" "%JANDEX_SRC%"
    if !ERRORLEVEL! EQU 0 (
        echo Jandex patche avec succes.
    ) else (
        echo ATTENTION: Le patch Jandex a echoue.
    )
) else (
    echo ATTENTION: Fichier Jandex ou fat JAR non trouve, patch ignore
)

REM Creer la structure de distribution
mkdir "dist-linux"
mkdir "dist-linux\lib"
mkdir "dist-linux\config"

REM Copier le JAR principal
echo Copie du JAR principal...
copy "target\core-thorntail.jar" "dist-linux\" >nul
if not exist "target\core-thorntail.jar" (
    copy "target\core.jar" "dist-linux\" >nul
)

REM Copier la configuration
echo Copie de la configuration...
if exist "project-defaults.yml" copy "project-defaults.yml" "dist-linux\config\" >nul

REM Creer le script de demarrage Linux
echo Creation du script de demarrage Linux...
copy "dist-linux-templates\start.sh" "dist-linux\start.sh" >nul

REM Creer un script systemd
echo Creation du fichier de service systemd...
(
echo [Unit]
echo Description=SBRW-Reloaded-Core Game Server
echo After=network.target mysql.service
echo.
echo [Service]
echo Type=simple
echo User=sbrw
echo WorkingDirectory=/opt/sbrw-core
echo ExecStart=/usr/bin/java -Xms512m -Xmx2048m -XX:+UseG1GC -jar /opt/sbrw-core/core-thorntail.jar
echo Restart=on-failure
echo RestartSec=10
echo StandardOutput=journal
echo StandardError=journal
echo.
echo [Install]
echo WantedBy=multi-user.target
) > "dist-linux\sbrw-core.service"

REM Creer un README pour le deploiement
echo Creation du guide de deploiement...
(
echo # Deploiement sur Linux
echo.
echo ## Installation
echo.
echo 1. Telecharger le dossier dist-linux sur votre serveur Linux
echo.
echo 2. Installer Java 11 si necessaire:
echo    ```bash
echo    sudo apt update
echo    sudo apt install openjdk-11-jre
echo    # Ou pour le JDK complet:
echo    # sudo apt install openjdk-11-jdk
echo    ```
echo.
echo 3. Rendre le script executable:
echo    ```bash
echo    chmod +x start.sh
echo    ```
echo.
echo 4. Lancer l'application:
echo    ```bash
echo    ./start.sh
echo    ```
echo.
echo ## Installation comme service systemd
echo.
echo 1. Copier les fichiers dans /opt:
echo    ```bash
echo    sudo mkdir -p /opt/sbrw-core
echo    sudo cp -r * /opt/sbrw-core/
echo    sudo chmod +x /opt/sbrw-core/start.sh
echo    ```
echo.
echo 2. Creer un utilisateur dedie:
echo    ```bash
echo    sudo useradd -r -s /bin/false sbrw
echo    sudo chown -R sbrw:sbrw /opt/sbrw-core
echo    ```
echo.
echo 3. Installer le service:
echo    ```bash
echo    sudo cp /opt/sbrw-core/sbrw-core.service /etc/systemd/system/
echo    sudo systemctl daemon-reload
echo    sudo systemctl enable sbrw-core
echo    sudo systemctl start sbrw-core
echo    ```
echo.
echo 4. Verifier le statut:
echo    ```bash
echo    sudo systemctl status sbrw-core
echo    sudo journalctl -u sbrw-core -f
echo    ```
echo.
echo ## Configuration
echo.
echo Editez le fichier `config/project-defaults.yml` pour configurer:
echo - La connexion a la base de donnees MySQL
echo - Les parametres SMTP pour les emails
echo - Les autres parametres du serveur
echo.
echo ## Ports
echo.
echo Par defaut, l'application ecoute sur le port 8080.
echo Assurez-vous que ce port est ouvert dans votre firewall:
echo ```bash
echo sudo ufw allow 8080/tcp
echo ```
) > "dist-linux\README.md"

REM Afficher les fichiers crees
echo.
echo ========================================
echo BUILD REUSSI !
echo ========================================
echo.
echo Package Linux cree dans: dist-linux\
echo.
echo Contenu:
dir /b "dist-linux"
echo.
echo Fichiers JAR:
dir "dist-linux\*.jar" 2>nul
echo.
echo ========================================
echo Prochaines etapes:
echo ========================================
echo 1. Transferez le dossier 'dist-linux' sur votre serveur Linux
echo 2. Consultez dist-linux\README.md pour les instructions
echo 3. Executez ./start.sh pour demarrer l'application
echo.

pause
