package com.stucray.limen.management.signup;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class SignupController {

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
        @RequestParam String username,
        @RequestParam String password,
        Model model
    ) {
        SignupForm form = new SignupForm(organizationName, slug, username, password);
        SignupService.SignupResult result = signupService.signup(form);

        if (result instanceof SignupService.SignupResult.Success success) {
            return "redirect:/manage/t/" + success.slug() + "/login?registered";
        }

        SignupService.SignupResult.Error error = (SignupService.SignupResult.Error) result;
        model.addAttribute("form", form);
        model.addAttribute("errorField", error.field());
        model.addAttribute("errorMessage", error.message());
        return "signup";
    }
}
