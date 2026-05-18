-- The bytes in tenant_signing_key.iv are the PBKDF2 salt used by
-- Encryptors.stronger(kek, salt) to derive the AES-256 key from the
-- deployment KEK. They are NOT the AES IV: Encryptors.stronger generates
-- a fresh random IV per encryption and prepends it to the ciphertext blob,
-- so the AES IV lives inside private_key_ciphertext.
--
-- The original column name (set in V1) actively misled the #293 debug pass.
-- Rename it to match what the bytes actually hold. Closes #296.
ALTER TABLE tenant_signing_key RENAME COLUMN iv TO pbkdf2_salt;
