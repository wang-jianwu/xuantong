package com.example.demo.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * author wangjianwu
 * date 2025/11/20 23:37
 */
@RestController
public class TestController {


    @Value("${demo.nimbus.aaa:123}")
    private String testValue;


    @GetMapping("/")
    public String test(){
        return testValue;
    }
}
