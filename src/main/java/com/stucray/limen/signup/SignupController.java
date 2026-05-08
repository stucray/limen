package com.stucray.limen.signup;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;

@Controller
class SignupController {

    private final SignupService signupService;

    public SignupController(SignupService signupService) {
        this.signupService = signupService;
    }

    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("form", new SignupForm("", "", "", ""));
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(
        @RequestParam String organizationName,
        @RequestParam String slug,
        @RequestParam String email,
        @RequestParam String password,
        Model model
    ) {
        SignupForm form = new SignupForm(organizationName, slug, email, password);
        SignupService.SignupResult result = signupService.signup(form);

        if (result instanceof SignupService.SignupResult.Success success) {
            // Land on the check-inbox page so the new tenant owner sees the
            // verification email instructions before they try to log in.
            // The forwarder /login is also tenant-aware, so we encode the slug
            // explicitly here rather than relying on it.
            return "redirect:" + UriComponentsBuilder
                .fromPath("/t/" + success.slug() + "/check-inbox")
                .queryParam("email", success.email())
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();
        }

        SignupService.SignupResult.Error error = (SignupService.SignupResult.Error) result;
        model.addAttribute("form", form);
        model.addAttribute("errorField", error.field());
        model.addAttribute("errorMessage", error.message());
        return "signup";
    }
}
