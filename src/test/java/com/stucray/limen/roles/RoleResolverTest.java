package com.stucray.limen.roles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoleResolver (role-validation precondition for membership updates)")
class RoleResolverTest {

    @Mock RoleRepository roleRepository;
    @InjectMocks RoleResolver roleResolver;

    private static final Long APP_ID = 100L;
    private static final Long OTHER_APP_ID = 200L;

    @Test
    @DisplayName("Single role belonging to the application is accepted")
    void singleRoleInApplicationIsAccepted() {
        given(roleRepository.findById(1L))
            .willReturn(Optional.of(new Role(1L, APP_ID, "viewer", null, LocalDateTime.now())));

        assertThatCode(() -> roleResolver.requireRolesInApplication(APP_ID, Set.of(1L)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Multiple roles, all belonging to the application, are accepted")
    void multipleRolesAllInApplicationAreAccepted() {
        given(roleRepository.findById(1L))
            .willReturn(Optional.of(new Role(1L, APP_ID, "viewer", null, LocalDateTime.now())));
        given(roleRepository.findById(2L))
            .willReturn(Optional.of(new Role(2L, APP_ID, "editor", null, LocalDateTime.now())));

        assertThatCode(() -> roleResolver.requireRolesInApplication(APP_ID, Set.of(1L, 2L)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Empty set is a no-op (no repository call)")
    void emptySetIsANoOp() {
        assertThatCode(() -> roleResolver.requireRolesInApplication(APP_ID, Set.of()))
            .doesNotThrowAnyException();
        verify(roleRepository, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("Null set is a no-op (defensive — callers normally pre-copy)")
    void nullSetIsANoOp() {
        assertThatCode(() -> roleResolver.requireRolesInApplication(APP_ID, null))
            .doesNotThrowAnyException();
        verify(roleRepository, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("Unknown role id throws 'Role not found: <id>' with the offending id in the message")
    void unknownRoleIdNamesTheId() {
        given(roleRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> roleResolver.requireRolesInApplication(APP_ID, Set.of(99L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Role not found: 99");
    }

    @Test
    @DisplayName("Role from a different application throws 'Role does not belong to this application'")
    void roleFromOtherApplicationIsRejected() {
        given(roleRepository.findById(7L))
            .willReturn(Optional.of(new Role(7L, OTHER_APP_ID, "stray", null, LocalDateTime.now())));

        assertThatThrownBy(() -> roleResolver.requireRolesInApplication(APP_ID, Set.of(7L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Role does not belong to this application");
    }

    @Test
    @DisplayName("Mixed set fails fast on the first offender (LinkedHashSet preserves order)")
    void mixedSetFailsFastOnFirstOffender() {
        // LinkedHashSet preserves insertion order; the first id (3) is unknown
        // and should trigger the throw before id 1 is consulted.
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(3L);
        ids.add(1L);
        given(roleRepository.findById(3L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> roleResolver.requireRolesInApplication(APP_ID, ids))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Role not found: 3");
    }
}
