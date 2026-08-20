// src/main/java/org/example/fqxs/controller/PageController.java
package org.example.fqxs.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        System.out.println("\n🌐 访问首页: /");
        return "redirect:/index.html";
    }
}