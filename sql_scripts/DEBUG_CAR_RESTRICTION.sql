-- Script de diagnostic pour la restriction de voitures
-- Exécutez ce script pour débugger le problème

-- 1. Vérifier la structure de la table EVENT
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT 
FROM INFORMATION_SCHEMA.COLUMNS 
WHERE TABLE_NAME = 'EVENT' AND COLUMN_NAME = 'carRestriction';

-- 2. Voir tous les événements avec leurs restrictions
SELECT id, name, carRestriction, isEnabled, isLocked 
FROM EVENT 
WHERE carRestriction IS NOT NULL;

-- 3. Voir toutes les voitures d'un persona spécifique (remplacez 123 par l'ID du persona)
SELECT c.id, c.name, c.baseCar, p.name as persona_name 
FROM CAR c 
JOIN PERSONA p ON c.personaId = p.ID 
WHERE p.ID = 123;  -- CHANGEZ 123 par l'ID de votre persona

-- 4. Test de correspondance manuelle
-- Remplacez 'bacmono' par votre restriction et 123 par votre persona ID
SELECT 
    e.id as event_id,
    e.name as event_name,
    e.carRestriction,
    c.id as car_id,
    c.name as car_name,
    CASE 
        WHEN c.name = 'bacmono' THEN 'MATCH EXACT'
        WHEN UPPER(c.name) = UPPER('bacmono') THEN 'MATCH IGNORECASE'
        ELSE 'NO MATCH'
    END as match_result
FROM EVENT e
CROSS JOIN CAR c
JOIN PERSONA p ON c.personaId = p.ID
WHERE e.carRestriction = 'bacmono'  -- Remplacez par votre restriction
  AND p.ID = 123                    -- Remplacez par votre persona ID
ORDER BY match_result DESC;

-- 5. Vérifier si le persona possède bien la voiture attendue
SELECT 
    p.name as persona_name,
    COUNT(c.id) as total_cars,
    COUNT(CASE WHEN c.name = 'bacmono' THEN 1 END) as bacmono_count
FROM PERSONA p
LEFT JOIN CAR c ON c.personaId = p.ID
WHERE p.ID = 123  -- Remplacez par votre persona ID
GROUP BY p.ID, p.name;