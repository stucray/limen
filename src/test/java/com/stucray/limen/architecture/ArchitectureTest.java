package com.stucray.limen.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
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

    /**
     * Public methods on @Service classes that have NO cross-package caller in the
     * main classpath as of writing. These have only same-package main callers
     * (typically a controller) plus a cross-package test caller, and are pending
     * the same repo-write-or-MockMvc substitution that PRs #235/#236/#237 applied
     * to the createX factories. Each entry should disappear when its corresponding
     * test refactor lands.
     *
     * <p>Plus one true-public-by-Java entry: {@code loadUserByUsername} implements
     * Spring Security's {@code UserDetailsService} interface, so Java forces it
     * public. That entry will not be narrowed; it documents the structural carve-out.
     */
    private static final Set<String> SERVICE_METHODS_AWAITING_NARROWING = Set.of(
        // Structural exemption: implements Spring Security's UserDetailsService.
        // Java forces public on interface implementations; this entry will not be narrowed.
        "com.stucray.limen.auth.TenantUserDetailsService.loadUserByUsername(java.lang.String)"
    );

    private static final List<Class<? extends Annotation>> REFLECTION_ENTRY_POINT_ANNOTATIONS = List.of(
        EventListener.class,
        TransactionalEventListener.class,
        ApplicationModuleListener.class,
        Scheduled.class,
        Async.class
    );

    @Test
    @DisplayName("Public methods on @Service classes have a main-classpath cross-package caller")
    void servicePublicMethodsHaveMainCrossPackageCaller() {
        methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(Service.class)
            .and().arePublic()
            .and(notReflectionEntryPoint())
            .and(methodFullNameNotIn(SERVICE_METHODS_AWAITING_NARROWING))
            .should(haveCrossPackageCallerInMainClasspath())
            .check(CLASSES);
    }

    private static DescribedPredicate<JavaMethod> notReflectionEntryPoint() {
        return new DescribedPredicate<>("not a Spring reflection entry point") {
            @Override
            public boolean test(JavaMethod m) {
                return REFLECTION_ENTRY_POINT_ANNOTATIONS.stream().noneMatch(m::isAnnotatedWith);
            }
        };
    }

    private static DescribedPredicate<JavaMethod> methodFullNameNotIn(Set<String> excluded) {
        return new DescribedPredicate<>("not in service-method narrowing-TODO list") {
            @Override
            public boolean test(JavaMethod m) {
                return !excluded.contains(m.getFullName());
            }
        };
    }

    private static ArchCondition<JavaMethod> haveCrossPackageCallerInMainClasspath() {
        return new ArchCondition<>("have a cross-package caller in the main classpath") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String declaringPackage = method.getOwner().getPackageName();
                boolean hasCrossPackageCaller = method.getAccessesToSelf().stream()
                    .map(access -> access.getOriginOwner().getPackageName())
                    .anyMatch(pkg -> !pkg.equals(declaringPackage));
                if (!hasCrossPackageCaller) {
                    events.add(SimpleConditionEvent.violated(method,
                        method.getFullName() + " is public on an @Service class but has no main-classpath "
                            + "cross-package caller. Narrow to package-private, or — if it's only public for "
                            + "a cross-package test — substitute repo writes / MockMvc in the test and "
                            + "narrow. Add to SERVICE_METHODS_AWAITING_NARROWING with a TODO if a clean-up "
                            + "is staged for a follow-up PR."));
                }
            }
        };
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
