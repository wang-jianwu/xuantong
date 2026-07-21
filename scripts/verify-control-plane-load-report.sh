#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-}"
REPORT="${2:-}"
EXPECTED_CRASH_TARGET="${3:-}"

usage() {
  echo "Usage: $0 split-topology <report.jsonl> [follower|leader]" >&2
  exit 2
}

if [[ "$MODE" != "split-topology" || -z "$REPORT" ]]; then
  usage
fi
if [[ -n "$EXPECTED_CRASH_TARGET" \
      && "$EXPECTED_CRASH_TARGET" != "follower" \
      && "$EXPECTED_CRASH_TARGET" != "leader" ]]; then
  usage
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to verify control-plane load reports" >&2
  exit 1
fi
if [[ ! -s "$REPORT" ]]; then
  echo "Control-plane load report is missing or empty: $REPORT" >&2
  exit 1
fi

jq -s -e -r --arg expected "$EXPECTED_CRASH_TARGET" '
  def require($condition; $message):
    if $condition then . else error($message) end;

  . as $rows
  | [$rows[] | select(.type == "header")] as $headers
  | [$rows[] | select(.type == "sample")] as $samples
  | [$rows[] | select(.type == "summary")] as $summaries
  | [$samples[] | select(.phase == "pre-crash")] as $preCrashSamples
  | [$samples[] | select(.phase == "post-crash")] as $postCrashSamples
  | [$samples[] | select(.phase == "final")] as $finalSamples
  | ($postCrashSamples + $finalSamples) as $growthSamples
  | require(($rows | length) >= 5;
      "report must contain header, pre-crash, post-crash, final, and summary rows")
  | require(all($rows[];
      .type == "header" or .type == "sample" or .type == "summary");
      "report contains an unsupported row type")
  | require(($headers | length) == 1;
      "report must contain exactly one header")
  | require(($summaries | length) == 1;
      "report must contain exactly one summary")
  | require(($preCrashSamples | length) >= 1
        and ($postCrashSamples | length) >= 1
        and ($finalSamples | length) == 1;
      "report must contain pre-crash, post-crash, and one final sample")
  | require($rows[0].type == "header";
      "header must be the first JSONL row")
  | require($rows[-1].type == "summary";
      "summary must be the final JSONL row")
  | require(all($rows[1:-1][]; .type == "sample");
      "only sample rows may appear between header and summary")
  | $headers[0] as $header
  | $summaries[0] as $summary
  | $finalSamples[0] as $finalSample
  | ($summary.profile.growthWarmupSeconds * 1000) as $warmupMs
  | ([$growthSamples[] | select(.phaseElapsedMs >= $warmupMs)][0]
      // $growthSamples[-1]) as $baselineSample
  | [$summary.nodes[] | select(.crashed == true)] as $crashed
  | [$summary.nodes[] | select(.crashed == false)] as $survivors
  | require(
      $header.schemaVersion == 1
        and $summary.schemaVersion == 1
        and $header.startedEpochMs == $summary.startedEpochMs
        and $header.runLabel == $summary.runLabel
        and $header.buildRevision == $summary.buildRevision
        and $header.buildState == $summary.buildState
        and $header.profile == $summary.profile
        and $header.runtime == $summary.runtime;
      "header and summary execution metadata are inconsistent")
  | require(
      $header.runtime.transportPath
          == "native-socketd-tcp+ratis-three-voter+three-child-jvms"
        and ($header.runtime.javaVersion | type) == "string"
        and ($header.runtime.javaVersion | length) > 0
        and ($header.runtime.javaVmName | length) > 0
        and ($header.runtime.javaVmVendor | length) > 0
        and ($header.runtime.socketdVersion | length) > 0
        and $header.runtime.socketdVersion != "unknown"
        and ($header.runtime.solonVersion | length) > 0
        and $header.runtime.solonVersion != "unknown"
        and ($header.runtime.ratisVersion | length) > 0
        and $header.runtime.ratisVersion != "unknown"
        and ($header.runtime.osName | length) > 0
        and ($header.runtime.osVersion | length) > 0
        and ($header.runtime.osArch | length) > 0
        and $header.runtime.availableProcessors > 0
        and $header.runtime.physicalMemoryBytes > 0
        and $header.runtime.driverMaxHeapBytes > 0;
      "report runtime metadata is incomplete")
  | require(
      $summary.profile.crashTarget == "follower"
        or $summary.profile.crashTarget == "leader";
      "profile.crashTarget must be follower or leader")
  | require($expected == ""
        or $summary.profile.crashTarget == $expected;
      "report crash target does not match the requested scenario")
  | require(
      $summary.topology.transport == "native-socketd-tcp"
        and $summary.topology.gatewayCount == 3
        and $summary.topology.stateVoterCount == 3
        and $summary.topology.singleActiveSessionPerClient == true
        and $summary.topology.sequentialFailover == true;
      "report does not describe the required native three-node topology")
  | require(
      ($summary.profile.crashTarget == "leader")
        == $summary.topology.crashedVoterWasLeader;
      "crashed voter role does not match profile.crashTarget")
  | require(all(range(1; $samples | length);
      $samples[.].elapsedMs >= $samples[. - 1].elapsedMs);
      "sample elapsedMs values must be monotonic")
  | require(all(range(1; $preCrashSamples | length);
      $preCrashSamples[.].phaseElapsedMs
        >= $preCrashSamples[. - 1].phaseElapsedMs)
      and all(range(1; $growthSamples | length);
      $growthSamples[.].phaseElapsedMs
        >= $growthSamples[. - 1].phaseElapsedMs);
      "sample phaseElapsedMs values must be monotonic within each phase")
  | require(all($samples[];
      (.phase == "pre-crash"
          or .phase == "post-crash"
          or .phase == "final")
        and all(.nodes[]; .stateAlive == true)
        and .clientMinActiveSessions >= 0
        and .clientMaxActiveSessions <= 1
        and .clientsWithMultipleActiveSessions == 0
        and .clientActiveSessions + .clientsWithoutActiveSession
          == $summary.profile.clients
        and .clientRegisteredWatches == $summary.profile.watchers
        and .requestAcceptedTotal >= .requestCompletedTotal);
      "a sample violates the phase, Session, Watch, or request invariant")
  | require(all($preCrashSamples[]; (.nodes | length) == 3)
        and all($growthSamples[]; (.nodes | length) == 2);
      "sample node count does not match its failure phase")
  | require($samples[-1].phase == "final";
      "the final resource sample must be the last sample row")
  | require(
      $finalSample.activeSessions == $summary.profile.clients
        and $finalSample.activeSubscriptions == $summary.profile.watchers
        and $finalSample.pendingWatchAcknowledgements == 0
        and $finalSample.inFlightRequests == 0
        and $finalSample.workQueueDepth == 0
        and $finalSample.stateCallbackQueueDepth == 0
        and $finalSample.clientActiveSessions == $summary.profile.clients
        and $finalSample.clientsWithoutActiveSession == 0
        and $finalSample.clientsWithMultipleActiveSessions == 0
        and $finalSample.clientInFlightRequests == 0
        and $finalSample.clientRegisteredWatches == $summary.profile.watchers
        and $finalSample.clientActiveSubscribeStreams
          == $summary.profile.watchers;
      "final sample did not converge to an idle and fully restored client shell")
  | require(
      $summary.resources.sampleCount == ($samples | length)
        and $summary.resources.preCrashSampleCount
          == ($preCrashSamples | length)
        and $summary.resources.growthSampleCount
          == ($growthSamples | length)
        and $summary.resources.growth.baselineElapsedMs
          == $baselineSample.elapsedMs
        and $summary.resources.growth.baselinePhaseElapsedMs
          == $baselineSample.phaseElapsedMs
        and $summary.resources.growth.lastElapsedMs
          == $finalSample.elapsedMs
        and $summary.resources.growth.lastPhaseElapsedMs
          == $finalSample.phaseElapsedMs
        and $summary.resources.growth.observationWindowMs
          == ($finalSample.elapsedMs - $baselineSample.elapsedMs);
      "resource sample count or growth window is inconsistent")
  | require(
      $summary.result.fetchSucceeded > 0
        and $summary.result.fetchFailed
          <= $summary.result.allowedFetchFailures
        and $summary.result.revisionRegressed == 0
        and $summary.result.publishSucceeded >= 2
        and $summary.result.publishFailed == 0
        and $summary.result.latestDecisionRevision > 0
        and $summary.result.latestEventRevision > 0;
      "request, publish, or revision result violates the failure contract")
  | require(
      if $summary.profile.crashTarget == "follower" then
        $summary.result.allowedFetchFailures == 0
          and $summary.result.fetchFailed == 0
      else
        $summary.result.allowedFetchFailures
          == $summary.profile.fetchConcurrency
      end;
      "fetch failure budget does not match the crashed voter role")
  | require(
      $summary.clientAfterClose.activeSessions == 0
        and $summary.clientAfterClose.inFlightRequests == 0
        and $summary.clientAfterClose.registeredWatches == 0
        and $summary.clientAfterClose.activeSubscribeStreams == 0;
      "client resources were not fully released after close")
  | require(
      ($summary.nodes | length) == 3
        and ($crashed | length) == 1
        and ($survivors | length) == 2
        and $crashed[0].final == null;
      "node report must contain one crashed and two surviving processes")
  | require(all($survivors[];
      .final != null
        and .final.stateAlive == true
        and .final.activeSessions == 0
        and .final.activeSubscriptions == 0
        and .final.pendingWatchAcknowledgements == 0
        and .final.inFlightRequests == 0
        and .final.workQueueDepth == 0
        and .final.stateCallbackQueueDepth == 0
        and .final.requestAcceptedTotal == .final.requestCompletedTotal
        and .final.sessionOpenedTotal == .final.sessionClosedTotal
        and .final.subscriptionOpenedTotal == .final.subscriptionClosedTotal
        and .final.tenantRateLimitedTotal == 0
        and .final.overloadedRejectedTotal == 0
        and .final.stateCallbackRejectedTotal == 0
        and .final.stateLastCommittedIndex == .final.stateLastAppliedIndex);
      "a surviving node did not converge or release all Gateway resources")
  | require(
      ([$survivors[].final | select(.stateLeader == true)] | length) == 1
        and ([$survivors[].final.stateLastCommittedIndex]
          | unique | length) == 1
        and ([$survivors[].final.stateLastAppliedIndex]
          | unique | length) == 1;
      "surviving voters did not converge to one leader and one applied index")
  | require(
      ([$summary.nodes[].warningLines] | add) < 200
        and ([$summary.nodes[].errorLines] | add) < 50;
      "process WARN/ERROR volume exceeded the bounded failure budget")
  | "Control-plane load report verified: target="
      + $summary.profile.crashTarget
      + ", samples=" + (($samples | length) | tostring)
      + ", preCrashSamples=" + (($preCrashSamples | length) | tostring)
      + ", growthSamples=" + (($growthSamples | length) | tostring)
      + ", fetchSucceeded=" + ($summary.result.fetchSucceeded | tostring)
      + ", fetchFailed=" + ($summary.result.fetchFailed | tostring)
' "$REPORT"
