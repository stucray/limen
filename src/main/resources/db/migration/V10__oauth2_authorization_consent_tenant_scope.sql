DROP TABLE IF EXISTS oauth2_authorization_consent;

CREATE TABLE oauth2_authorization_consent (
    tenant_id bigint NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (tenant_id, registered_client_id, principal_name)
);
