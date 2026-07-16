package com.example.demo.controller;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;

@Controller
public class HelloController {

    @Get
    @Mapping("/hello")
    public String hello(@Param(defaultValue = "world") String name) {
        return String.format("Hello %s!", name);
    }
}
