package cloud.xuantong.core.service;

import cloud.xuantong.core.model.Environment;
import cloud.xuantong.core.repository.EnvironmentRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.data.annotation.Transaction;

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

    public boolean updateEnvironment(Environment env) {
        return environmentRepository.update(env) > 0;
    }

    @Transaction
    public boolean setDefaultEnvironment(String code) {
        return environmentRepository.setDefault(code) > 0;
    }

    public boolean deleteEnvironment(String code) {
        return environmentRepository.delete(code) > 0;
    }
}