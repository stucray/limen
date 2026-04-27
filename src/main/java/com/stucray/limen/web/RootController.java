package com.stucray.limen.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootController {

    @GetMapping("/")
    public String redirectToSystemLogin() {
        return "redirect:/manage/t/system/login";
    }
}
