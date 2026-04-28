package com.stucray.limen.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class RedirectLoginController {

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String slug) {
        if (slug == null || slug.isBlank()) {
            return "redirect:/";
        }
        return "redirect:/manage/t/" + slug + "/login";
    }
}
