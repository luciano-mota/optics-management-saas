-- Permissões explicitamente escolhidas no convite, aplicadas só após o aceite.
ALTER TABLE member_invitations
    ADD COLUMN permissions_csv TEXT NOT NULL DEFAULT '',
    ADD COLUMN permissions_selected BOOLEAN NOT NULL DEFAULT FALSE;
