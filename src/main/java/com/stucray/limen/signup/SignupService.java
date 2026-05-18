package com.stucray.limen.signup;

import com.stucray.limen.provisioning.TenantProvisioner;
import com.stucray.limen.provisioning.TenantProvisioner.NewTenantRequest;
import org.springframework.stereotype.Service;

/**
 * Thin adapter over {@link TenantProvisioner} for the public {@code /signup}
 * form. The orchestration, validation, and atomicity all live in the deep
 * module; this class just translates the form's wire contract into a
 * {@link TenantProvisioner.Result} and rewraps it as {@link SignupResult} so
 * existing call sites (the controller, audit-flow integration tests) keep
 * working.
 */
@Service
public class SignupService {

    private final TenantProvisioner tenantProvisioner;

    SignupService(TenantProvisioner tenantProvisioner) {
        this.tenantProvisioner = tenantProvisioner;
    }

    public sealed interface SignupResult {
        record Success(String slug, String email) implements SignupResult {}
        record Error(String field, String message) implements SignupResult {}
    }

    SignupResult signup(SignupForm form) {
        return switch (tenantProvisioner.provision(NewTenantRequest.fromSignupForm(
            form.slug(), form.organizationName(), form.email(), form.fullName(), form.password()
        ))) {
            case TenantProvisioner.Result.Provisioned p ->
                new SignupResult.Success(p.tenant().slug(), p.ownerEmail());
            case TenantProvisioner.Result.Rejected r ->
                new SignupResult.Error(r.field(), r.message());
        };
    }
}
