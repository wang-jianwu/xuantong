package cloud.xuantong.server.admin.controller;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.model.User;
import cloud.xuantong.discovery.management.model.ServiceDefinition;
import cloud.xuantong.discovery.management.model.ServiceSnapshot;
import cloud.xuantong.discovery.management.model.ServiceInstance;
import cloud.xuantong.discovery.management.service.ServiceDefinitionService;
import cloud.xuantong.server.state.management.RegistryStateManagementService;
import cloud.xuantong.server.state.management.ServiceDefinitionLifecycleCoordinator;
import cloud.xuantong.server.admin.security.AdminSecurityContext;
import cloud.xuantong.config.management.service.AuditLogService;
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
import java.util.Comparator;
import java.util.Map;

@Controller
@Mapping("/api/v2/namespaces/{namespaceId}/groups/{groupName}/services")
public class ServiceController {
    @Inject
    private ServiceDefinitionService serviceDefinitionService;
    @Inject
    private RegistryStateManagementService registryState;
    @Inject
    private ServiceDefinitionLifecycleCoordinator lifecycle;
    @Inject
    private AuditLogService auditLogService;

    @Get
    @Mapping
    public Result<PageResult<ServiceDefinition>> findByGroup(
            @Path String namespaceId,
            @Path String groupName,
            @Param(defaultValue = "") String keyword,
            @Param(defaultValue = "") String lifecycleState,
            @Param(defaultValue = "1") int page,
            @Param(defaultValue = "20") int pageSize) {
        return Result.succeed(serviceDefinitionService.findPageByGroup(
                namespaceId, groupName, keyword, lifecycleState,
                new PageQuery(page, pageSize)));
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
            String operator = currentUser(context);
            ServiceDefinition created = lifecycle.create(service, operator);
            auditLogService.record(
                    namespaceId, groupName, "SERVICE", created.getServiceName(),
                    "SERVICE_CREATED", operator,
                    Map.of("lifecycleState", created.getLifecycleState()),
                    context.remoteIp(), null);
            return Result.succeed(created);
        } catch (IllegalArgumentException | IllegalStateException e) {
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
            @Body ServiceDefinition changes,
            Context context) {
        try {
            ServiceDefinition updated = serviceDefinitionService.update(
                    namespaceId, groupName, serviceName, changes);
            auditLogService.record(
                    namespaceId, groupName, "SERVICE", serviceName,
                    "SERVICE_UPDATED", currentUser(context),
                    Map.of("serviceName", serviceName), context.remoteIp(), null);
            return Result.succeed(updated);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Delete
    @Mapping("/{serviceName}")
    public Result<String> delete(
            @Path String namespaceId,
            @Path String groupName,
            @Path String serviceName,
            Context context) {
        try {
            boolean deleted = lifecycle.delete(namespaceId, groupName, serviceName);
            if (!deleted) {
                return Result.failure("Service does not exist");
            }
            auditLogService.record(
                    namespaceId, groupName, "SERVICE", serviceName,
                    "SERVICE_DELETED", currentUser(context),
                    Map.of("serviceName", serviceName), context.remoteIp(), null);
            return Result.succeed("Service deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Get
    @Mapping("/{serviceName}/instances")
    public Result<PageResult<ServiceInstance>> instances(
            @Path String namespaceId,
            @Path String groupName,
            @Path String serviceName,
            @Param(defaultValue = "false") boolean onlyAvailable,
            @Param(defaultValue = "1") int page,
            @Param(defaultValue = "20") int pageSize) {
        try {
            PageQuery pageQuery = new PageQuery(page, pageSize);
            ServiceSnapshot snapshot = registryState.snapshot(
                    namespaceId, groupName, serviceName, onlyAvailable);
            List<ServiceInstance> instances = snapshot.instances().stream()
                    .sorted(Comparator.comparing(
                            ServiceInstance::getInstanceId,
                            Comparator.nullsLast(String::compareTo)))
                    .toList();
            int fromIndex = (int) Math.min(pageQuery.offset(), instances.size());
            int toIndex = Math.min(fromIndex + pageQuery.pageSize(), instances.size());
            PageResult<ServiceInstance> result = PageResult.of(
                    pageQuery, instances.size(), instances.subList(fromIndex, toIndex))
                    .withMetadata(Map.of(
                            "service", snapshot.service(),
                            "revision", snapshot.revision(),
                            "onlyAvailable", onlyAvailable));
            return Result.succeed(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Delete
    @Mapping("/{serviceName}/instances/{instanceId}")
    public Result<String> deregister(
            @Path String namespaceId,
            @Path String groupName,
            @Path String serviceName,
            @Path String instanceId,
            Context context) {
        try {
            String operator = currentUser(context);
            boolean evicted = registryState.evict(
                    namespaceId, groupName, serviceName, instanceId, operator);
            if (!evicted) {
                return Result.failure("Service instance does not exist");
            }
            auditLogService.record(
                    namespaceId, groupName, "SERVICE_INSTANCE", instanceId,
                    "SERVICE_INSTANCE_EVICTED", operator,
                    Map.of("serviceName", serviceName, "instanceId", instanceId),
                    context.remoteIp(), null);
            return Result.succeed("Service instance deregistered");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    private String currentUser(Context context) {
        User user = AdminSecurityContext.currentUser(context);
        return user == null ? "system" : user.getUsername();
    }
}
