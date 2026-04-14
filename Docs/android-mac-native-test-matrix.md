# Android Mac-Native Test Matrix

Historical note: this matrix covers the previous Mac-native/native-client transport work. It is no longer the active shipped Android runtime path; see [Android Mirror-Shell Architecture](./android-mirror-shell-architecture.md).

This matrix records the automated coverage used to validate the converged Android Mac-native path.

The goal is not to re-test the legacy bridge. It is to prove that Android can pair, authenticate, hydrate, recover, and dispatch canonical actions against the same Mac-native backend contract used by the web client.

## Automated Coverage

- Fresh pair on Android against the Mac server
  - `io.androdex.android.ui.pairing.PairingPayloadValidatorTest`
  - `io.androdex.android.data.MacNativeAndrodexBackendClientTest.connectWithPairingPayload_bootstrapsSnapshotAndSubscribes`
- Saved reconnect after Android app restart
  - `io.androdex.android.data.MacNativeAndrodexBackendClientTest.reconnectSaved_usesPersistedSessionAndRefreshesSnapshot`
- Reconnect after Mac server restart
  - `io.androdex.android.data.MacNativeAndrodexBackendClientTest.reconnectSaved_usesPersistedSessionAndRefreshesSnapshot`
  - The fake auth/session transport intentionally re-validates a persisted bearer session instead of trusting old local state.
- Reconnect after network drop with replay recovery
  - `io.androdex.android.transport.macnative.MacNativeRecoveryCoordinatorTest.classifyDomainEvent_requiresRecoveryForSequenceGap`
  - `io.androdex.android.transport.macnative.MacNativeRecoveryCoordinatorTest.completeReplayRecovery_requestsRetryWhenFrontierStillAhead`
  - `io.androdex.android.transport.macnative.MacNativeRecoveryCoordinatorTest.completeSnapshotRecovery_requestsReplayWhenDeferredEventsArrived`
- Android opening a thread created on Mac or web
  - `io.androdex.android.transport.macnative.MacNativeSnapshotMapperTest.snapshotMapping_decodesThreadSummaryAndLoadResult`
  - `io.androdex.android.data.MacNativeAndrodexBackendClientTest.connectWithPairingPayload_bootstrapsSnapshotAndSubscribes`
- Mac or web opening a thread updated on Android and seeing the same state
  - `io.androdex.android.data.MacNativeAndrodexBackendClientTest.canonicalActions_dispatchExpectedCommands`
  - Canonical dispatch coverage proves Android writes through the Mac server command surface instead of a shadow bridge protocol.
- Approvals resolved on one client and reflected correctly on the other
  - `io.androdex.android.transport.macnative.MacNativeSnapshotMapperTest.pendingState_usesActivitiesForApprovalsAndUserInputs`
- User-input prompts resolved on one client and cleared on the other
  - `io.androdex.android.transport.macnative.MacNativeSnapshotMapperTest.pendingState_usesActivitiesForApprovalsAndUserInputs`
- Interrupt, rollback, and session stop from Android while Mac or web is open
  - `io.androdex.android.data.MacNativeAndrodexBackendClientTest.canonicalActions_dispatchExpectedCommands`
- Sequence-gap recovery and duplicate suppression
  - `io.androdex.android.transport.macnative.MacNativeRecoveryCoordinatorTest.markEventBatchApplied_advancesLatestSequence`
  - `io.androdex.android.transport.macnative.MacNativeRecoveryCoordinatorTest.deriveReplayRetryDecision_stopsAfterConfiguredNoProgressRetries`
- Auth expiry and re-pair or re-auth flows
  - `io.androdex.android.data.MacNativeAndrodexBackendClientTest.reconnectSaved_clearsExpiredAuthAndRequiresRepair`
- Mac-native HTTP auth and orchestration contract coverage
  - `io.androdex.android.transport.macnative.OkHttpMacNativeHttpTransportTest`

## Commands Used

The focused Android verification command for this convergence slice is:

```sh
cd android
./gradlew :app:testDebugUnitTest \
  --tests io.androdex.android.data.MacNativeAndrodexBackendClientTest \
  --tests io.androdex.android.transport.macnative.MacNativeRecoveryCoordinatorTest \
  --tests io.androdex.android.transport.macnative.MacNativeSnapshotMapperTest \
  --tests io.androdex.android.transport.macnative.OkHttpMacNativeHttpTransportTest \
  --tests io.androdex.android.ui.pairing.PairingPayloadValidatorTest \
  --tests io.androdex.android.service.AndrodexServiceTest
```

## Scope Note

This matrix is intentionally centered on the canonical Mac-native client path plus the Android service integration around it.

Legacy bridge flows, desktop refresh helpers, and relay-owned compatibility paths are not part of the convergence acceptance criteria anymore.
