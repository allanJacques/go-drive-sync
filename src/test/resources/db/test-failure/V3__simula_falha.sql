-- H2 confirma alguns comandos DDL antes da falha seguinte; exercitamos esse cenário real.
ALTER TABLE accounts ADD COLUMN partial_change INTEGER;
INSERT INTO tabela_inexistente (id) VALUES (1);
