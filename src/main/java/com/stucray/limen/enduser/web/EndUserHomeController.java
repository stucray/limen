package com.stucray.limen.enduser.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
class EndUserHomeController {

    // Every authenticated principal in a non-system tenant holds
    // ROLE_TENANT_OWNER, and every principal in the system tenant holds
    // ROLE_SYSTEM_ADMIN (see TenantUserDetails) — so the only authed visitors
    // to /t/{slug}/ today are tenant managers, whose next legitimate action
    // is on the management surface (issue #283). The redirect retires the
    // pre-existing dead-end page; when an "end-user-only" role enters the
    // model, restore a render branch here.
    @GetMapping("/t/{slug}/")
    String home(@PathVariable String slug) {
        return "redirect:/manage/t/" + slug + "/";
    }
}
