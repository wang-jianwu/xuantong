package cloud.xuantong.registry.state;

public sealed interface RegistryMutation permits RegisterLease, RenewLeaseBatch,
        DeregisterLease, TakeoverLease, ExpireLeaseBatch, EvictLease,
        ActivateServiceDefinition, DeleteServiceDefinition {

    RegistryActor actor();

    long observedTimeEpochMs();
}
