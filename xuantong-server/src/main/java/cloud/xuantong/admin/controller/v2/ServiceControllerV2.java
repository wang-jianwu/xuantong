package cloud.xuantong.admin.controller.v2;

import cloud.xuantong.core.model.User;
import cloud.xuantong.core.v2.model.ServiceDefinition;
import cloud.xuantong.core.v2.model.ServiceInstance;
import cloud.xuantong.core.v2.model.ServiceSnapshot;
import cloud.xuantong.core.v2.service.ServiceDefinitionService;
import cloud.xuantong.core.v2.service.ServiceInstanceRegistry;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Delete;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Path;
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Put;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.util.List;

@Controller
@Mapping("/api/v2/namespaces/{namespaceId}/groups/{groupName}/services")
public class ServiceControllerV2 {
    @Inject
    private ServiceDefinitionService serviceDefinitionService;
    @Inject
    private ServiceInstanceRegistry instanceRegistry;

    @Get
    @Mapping
    public Result<List<ServiceDefinition>> findByGroup(
            @Path String namespaceId, @Path String groupName) {
        return Result.succeed(serviceDefinitionService.findByGroup(namespaceId, groupName));
    }

    @Post
    @Mapping
    public Result<ServiceDefinition> create(
            @Path String namespaceId,
            @Path String groupName,
            @Body ServiceDefinition service,
            Context context) {
        try {
            service.setNamespaceId(namespaceId);
            service.setGroupName(groupName);
            return Result.succeed(serviceDefinitionService.create(service, currentUser(context)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Get
    @Mapping("/{serviceName}")
    public Result<ServiceDefinition> find(
            @Path String namespaceId, @Path String groupName, @Path String serviceName) {
        ServiceDefinition service = serviceDefinitionService.find(namespaceId, groupName, serviceName);
        return service == null ? Result.failure("Service does not exist") : Result.succeed(service);
    }

    @Put
    @Mapping("/{serviceName}")
    public Result<ServiceDefinition> update(
            @Path String namespaceId,
            @Path String groupName,
            @Path String serviceName,
            @Body ServiceDefinition changes) {
        try {
            return Result.succeed(serviceDefinitionService.update(
                    namespaceId, groupName, serviceName, changes));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Delete
    @Mapping("/{serviceName}")
    public Result<String> delete(
            @Path String namespaceId, @Path String groupName, @Path String serviceName) {
        try {
            return serviceDefinitionService.delete(namespaceId, groupName, serviceName)
                    ? Result.succeed("Service deleted")
                    : Result.failure("Service does not exist");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Get
    @Mapping("/{serviceName}/instances")
    public Result<ServiceSnapshot> instances(
            @Path String namespaceId,
            @Path String groupName,
            @Path String serviceName,
            @Param(defaultValue = "false") boolean onlyAvailable) {
        return Result.succeed(instanceRegistry.snapshot(
                namespaceId, groupName, serviceName, onlyAvailable));
    }

    @Post
    @Mapping("/{serviceName}/instances")
    public Result<ServiceInstance> register(
            @Path String namespaceId,
            @Path String groupName,
            @Path String serviceName,
            @Body ServiceInstance instance) {
        try {
            return Result.succeed(instanceRegistry.register(
                    namespaceId, groupName, serviceName, instance));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Post
    @Mapping("/{serviceName}/instances/{instanceId}/heartbeat")
    public Result<ServiceInstance> heartbeat(
            @Path String namespaceId,
            @Path String groupName,
            @Path String serviceName,
            @Path String instanceId,
            @Param String leaseId) {
        try {
            return Result.succeed(instanceRegistry.heartbeat(
                    namespaceId, groupName, serviceName, instanceId, leaseId));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Delete
    @Mapping("/{serviceName}/instances/{instanceId}")
    public Result<String> deregister(
            @Path String namespaceId,
            @Path String groupName,
            @Path String serviceName,
            @Path String instanceId) {
        try {
            return instanceRegistry.forceDeregister(
                    namespaceId, groupName, serviceName, instanceId)
                    ? Result.succeed("Service instance deregistered")
                    : Result.failure("Service instance does not exist");
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    private String currentUser(Context context) {
        User user = context.session("user", User.class);
        return user == null ? "system" : user.getUsername();
    }
}
