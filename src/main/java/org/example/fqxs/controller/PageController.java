package org.example.fqxs.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "redirect:/index.html";
    }

    /** 登录页：静态页，路由留一个 /login 便于手输地址与拦截器回跳。 */
    @GetMapping("/login")
    public String login() {
        return "redirect:/login.html";
    }

    /** 失败日志页：独立静态页，跳转入口在首页工具栏的 AI 簇里。 */
    @GetMapping("/faults")
    public String faults() {
        return "redirect:/faults.html";
    }

    /** 审查记录页：章节拉取 / 正在审查 / 判定结果，同样挂在首页 AI 簇。 */
    @GetMapping("/records")
    public String records() {
        return "redirect:/records.html";
    }
}
