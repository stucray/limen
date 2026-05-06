package com.stucray.limen.architecture;

import com.stucray.limen.LimenApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class LimenModuleArchitectureTest {

    @Test
    void verifies_module_boundaries() {
        ApplicationModules.of(LimenApplication.class).verify();
    }
}
