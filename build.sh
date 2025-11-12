#!/bin/bash
echo "Building SBRW-Reloaded-Core..."
echo

# Vérifier si Maven est installé
if ! command -v mvn &> /dev/null; then
    echo "ERREUR: Maven n'est pas installé ou pas dans le PATH"
    echo "Veuillez installer Maven depuis https://maven.apache.org/download.cgi"
    exit 1
fi

echo "Maven trouvé, démarrage du build..."
echo "Commande: mvn clean package -e \"-Dnfs.core.stage=production\""
echo

# Exécuter la commande Maven
mvn clean package -e "-Dnfs.core.stage=production"

if [ $? -eq 0 ]; then
    echo
    echo "========================================"
    echo "BUILD RÉUSSI !"
    echo "========================================"
    echo
    echo "Le fichier JAR généré se trouve dans le dossier target/"
    ls -la target/*.jar 2>/dev/null || echo "Aucun JAR trouvé dans target/"
else
    echo
    echo "========================================"
    echo "BUILD ÉCHOUÉ !"
    echo "========================================"
    echo "Vérifiez les erreurs ci-dessus"
fi

echo
read -p "Appuyez sur Entrée pour continuer..."