package com.stucray.limen.enduser.web;

import com.stucray.limen.user.TenantUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class EndUserHomeController {

    // The OAuth end-user surface home. Reached only by an authenticated
    // principal whose tenant matches the URL slug — unauthenticated hits are
    // bounced to /t/{slug}/login by the security chain, and cross-tenant hits
    // are force-logged-out by TenantAccessFilter.
    //
    // Renders a neutral, self-contained page rather than redirecting into the
    // management console. An OAuth end-user whose /oauth2/authorize could not
    // be resumed (e.g. the saved request expired with the session) falls
    // through the post-login intent chain to here; depositing that principal on
    // the /manage admin surface is an authorization-scope leak (issue #327),
    // "safe" today only by the accident that every tenant user is an owner.
    // Tenant managers reach the console via /manage/t/{slug}/login independently
    // (the landing-page forwarder routes /login?slug= there), so no legitimate
    // console visitor depends on a bounce from this route.
    @GetMapping("/t/{slug}/")
    String home(@AuthenticationPrincipal TenantUserDetails principal, Model model) {
        model.addAttribute("tenantName", principal.tenant().displayName());
        return "enduser/home";
    }
}
