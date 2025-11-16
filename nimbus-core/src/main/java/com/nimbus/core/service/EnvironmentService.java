package com.nimbus.core.service;

import com.nimbus.core.model.Environment;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import com.nimbus.core.repository.EnvironmentRepository;

import java.util.List;

@Component
public class EnvironmentService {
    @Inject
    private EnvironmentRepository environmentRepository;

    public List<Environment> getAllEnvironments() {
        return environmentRepository.findAll();
    }

    public Environment getEnvironment(String code) {
        return environmentRepository.findByCode(code);
    }

    public boolean saveEnvironment(Environment env) {
        return environmentRepository.save(env) > 0;
    }

    public boolean setDefaultEnvironment(String code) {
        return environmentRepository.setDefault(code) > 0;
    }
}