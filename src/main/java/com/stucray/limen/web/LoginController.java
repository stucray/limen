package com.stucray.limen.web;

import com.stucray.limen.oauth2.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(Model model) {
        String slug = TenantContext.getSlug();
        if (slug != null) {
            model.addAttribute("tenantSlug", slug);
        }
        return "login";
    }
}
