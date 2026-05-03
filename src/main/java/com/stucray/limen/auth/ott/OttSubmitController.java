package com.stucray.limen.auth.ott;

import com.stucray.limen.tenant.TenantRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Renders the auto-submitting form for a magic-link click.
 *
 * <p>Spring Security's {@code OneTimeTokenAuthenticationFilter} only handles
 * POST, but a user clicking an email link issues a GET. Spring ships a
 * {@code DefaultOneTimeTokenSubmitPageGeneratingFilter} that renders an
 * equivalent page, but its form action is the literal configured
 * {@code loginProcessingUrl} — which contains a {@code *} wildcard in our
 * tenant-prefixed setup ({@code /t/*&#47;login/ott}) and therefore cannot be
 * submitted directly. We render our own page so the form action carries the
 * resolved slug.
 */
@Controller
public class OttSubmitController {

    private final TenantRepository tenantRepository;

    public OttSubmitController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @GetMapping("/t/{slug}/login/ott")
    public String submitForm(
        @PathVariable String slug,
        @RequestParam(required = false) String token,
        Model model
    ) {
        if (tenantRepository.findBySlug(slug).isEmpty()) {
            return "redirect:/";
        }
        model.addAttribute("slug", slug);
        model.addAttribute("token", token == null ? "" : token);
        return "ott-submit";
    }
}
