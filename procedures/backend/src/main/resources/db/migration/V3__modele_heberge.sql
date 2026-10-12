-- Agents : traçabilité des appels au modèle de langage auto-hébergé (API compatible OpenAI : Ollama, vLLM, LM Studio…)
ALTER TABLE studio.ai_run
    ADD COLUMN llm_calls    INT,          -- appels au modèle pendant l'exécution
    ADD COLUMN llm_failures INT,          -- appels en échec (serveur injoignable, réponse non JSON) : l'agent continue en heuristique
    ADD COLUMN llm_millis   BIGINT,       -- durée cumulée des appels réussis
    ADD COLUMN output_hash  CHAR(64);     -- empreinte SHA-256 des réponses reçues (preuve de ce que le modèle a renvoyé)
