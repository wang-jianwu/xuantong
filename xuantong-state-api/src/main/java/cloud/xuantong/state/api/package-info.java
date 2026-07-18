/**
 * Transport-neutral contracts between Gateway, Raft adapters, and deterministic
 * Config/Registry state machines.
 *
 * <p>This package deliberately contains no Socket.D, Ratis, Solon, database,
 * clock, or network types. A state-machine apply implementation must derive all
 * decisions from the command, the committed apply context, and replicated state.</p>
 */
package cloud.xuantong.state.api;
