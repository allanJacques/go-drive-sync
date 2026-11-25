-- Contas existentes começam sem posição confirmada para a leitura incremental do Drive.
ALTER TABLE accounts ADD COLUMN changes_page_token TEXT;
