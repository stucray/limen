package com.stucray.limen.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class RootController {

    @GetMapping("/")
    String landing() {
        return "landing";
    }
}
