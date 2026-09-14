-- P4 retire the legacy business access credential tables. V2 uses application_key exclusively.
DROP TABLE IF EXISTS light_ai.access_credential_alias;
DROP TABLE IF EXISTS light_ai.access_credential;
