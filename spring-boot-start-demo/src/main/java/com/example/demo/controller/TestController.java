package com.example.demo.controller;

import cloud.xuantong.client.annotation.ConfigValue;
import com.example.demo.conf.AnnotationExamples;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * author 封于修
 * date 2025/11/20 23:37
 */
@RestController
public class TestController {

    @Autowired
    private AnnotationExamples.BasicConfigService basicConfigService;

    @Autowired
    private AnnotationExamples.ComplexConfigService complexConfigService;
    @GetMapping("/")
    public Object test(){
        return basicConfigService.getAppName() + complexConfigService.getAppConfig() + complexConfigService.getPaymentConfig();
    }
}
