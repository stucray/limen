package com.stucray.limen.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static java.util.stream.Collectors.toUnmodifiableSet;

/**
 * Architectural rules enforced at test time. Cycle-freedom is covered by
 * {@link LimenModuleArchitectureTest} via Spring Modulith's
 * {@code ApplicationModules.verify()}. Add project-specific rules below;
 * if a rule is disabled, document the carve-out reason on @Disabled.
 */
@DisplayName("Architectural rules hold")
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(DO_NOT_INCLUDE_TESTS)
        .importPackages("com.stucray.limen");

    /**
     * Pre-existing public @Component classes that have legitimate cross-package
     * consumers (services injected by sibling-module controllers/configs, or
     * referenced by test fixtures across module boundaries). Locked here as an
     * explicit carve-out so the rule still catches *new* drift — additions to
     * this set should justify themselves at PR review.
     */
    private static final Set<String> COMPONENTS_PUBLIC_BY_NECESSITY = Set.of(
        "com.stucray.limen.applications.ApplicationLookup",
        "com.stucray.limen.applications.ApplicationService",
        "com.stucray.limen.audit.AuditEventWriter",
        "com.stucray.limen.auth.TenantAuthProvider",
        "com.stucray.limen.auth.TenantUserDetailsService",
        "com.stucray.limen.auth.ott.OttCompletionService",
        "com.stucray.limen.auth.ott.OttDispatcher",
        "com.stucray.limen.auth.ott.TenantAwareOneTimeTokenService",
        "com.stucray.limen.auth.ott.TenantOttAuthenticationProvider",
        "com.stucray.limen.clients.ClientManagementService",
        "com.stucray.limen.memberships.ApplicationMembershipService",
        "com.stucray.limen.memberships.ClientMembershipQuery",
        "com.stucray.limen.memberships.ClientMembershipService",
        "com.stucray.limen.memberships.UserMembershipPortfolioQuery",
        "com.stucray.limen.provisioning.TenantProvisioner",
        "com.stucray.limen.provisioning.TenantProvisioningService",
        "com.stucray.limen.roles.RoleManagementService",
        "com.stucray.limen.roles.RoleResolver",
        "com.stucray.limen.signup.SignupService",
        "com.stucray.limen.useradmin.UserAdministrationService"
    );

    @Test
    @DisplayName("@Component classes are package-private (except documented cross-package carve-outs)")
    void componentsArePackagePrivate() {
        classes().that().areMetaAnnotatedWith(Component.class)
            .and().areNotMetaAnnotatedWith(Configuration.class)
            .and(notIn(COMPONENTS_PUBLIC_BY_NECESSITY))
            .should().notBePublic().check(CLASSES);
    }

    @Test
    @DisplayName("@Configuration classes are package-private (except AutoConfigurations)")
    void configsArePackagePrivate() {
        Set<String> autoConfigs = readAutoConfigurationImports();
        classes().that().areAnnotatedWith(Configuration.class)
            .and(notIn(autoConfigs))
            .should().notBePublic().check(CLASSES);
    }

    private static Set<String> readAutoConfigurationImports() {
        Path imports = Paths.get("src/main/resources/META-INF/spring/"
            + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        if (!Files.exists(imports)) return Set.of();
        try (Stream<String> lines = Files.lines(imports)) {
            return lines.map(String::trim)
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .collect(toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DescribedPredicate<JavaClass> notIn(Set<String> excluded) {
        return new DescribedPredicate<>("not listed in AutoConfiguration.imports") {
            @Override
            public boolean test(JavaClass c) {
                return !excluded.contains(c.getName());
            }
        };
    }
}
