-- Migração hipotética exclusiva dos testes: comprova ALTER TABLE preservando registros.
ALTER TABLE accounts ADD COLUMN display_name VARCHAR(255);
