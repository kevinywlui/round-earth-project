# JVM/Robolectric test-suite plan (decided after 5-lens review)

> Status: TIER 1 + TIER 2 LANDED. Tier 1 (Room persistence) is in
> `BikePersistenceTest.kt` + `FakeBikeDataSource.kt` (8 tests). Tier 2 (BLE state machine,
> Approach C) is in `CscConnectionTest.kt` (7 tests: happy-path spike, CCCD-fail two-step,
> both CAS gates, the `!ready`-clause measurement-after-disconnect sibling, null connectGatt,
> count-jump-across-reconnect) — the inline-mock-maker
> feasibility spike PASSED under JDK 21 + Robolectric. Production seams: a `handleScan`
> extraction + `onScanResultForTest`/`pairForTest`/`connectionCallbackForTest` hooks and
> `reconnect` made `@VisibleForTesting internal`. Deps: `mockito-core:5.14.2`,
> `mockito-kotlin:5.4.0`, and `-XX:+EnableDynamicAgentLoading` test JVM arg. Full suite: 72
> tests green. Sections 4/7 below are the design of record.
>
> **Iteration-3 implementation realities (discovered while landing Tier 1 — these CORRECT the
> earlier plan):**
> - **Do NOT rebind Room's executors to a `TestCoroutineScheduler`.** `setQueryExecutor(test
>   dispatcher)` DEADLOCKS (`IllegalStateException` at `RoomDatabase.kt:439`): Room's open/query
>   path posts to the executor and then blocks on it, while virtual time only advances when a
>   coroutine drives it. Tier 1 runs on a REAL dispatcher under `runBlocking`; the two tests that
>   drive the repository's `start()` collect loop await the observable result with a bounded
>   poll (`awaitNonNull`, `withTimeout(5s) + delay(5)`) instead of `advanceUntilIdle`. The
>   pure-DAO tests just suspend on the real Room call. **Tier 2 must heed this:** its synchronous
>   captured-callback tests don't touch Room, so they stay on virtual time fine — but any future
>   end-to-end test mixing Room + a test scheduler will hit the same deadlock.
> - **`androidx.room:room-testing` IS pulled in** (provides the in-memory builder helpers cleanly
>   under Robolectric); Section 3's "NOT room-testing" was wrong in practice. Actual Tier-1 deps:
>   `robolectric:4.14.1`, `androidx.test:core`, `androidx.room:room-testing`, `kotlinx-coroutines-test`.
> - **SDK pin is a `robolectric.properties` file (`sdk=34`)**, project-wide, not a per-class
>   `@Config` — so exactly one `android-all` jar downloads and no author can forget it. (Tier 2's
>   TIRAMISU+ `writeDescriptor` branch still executes at any sdk≥33.) compileSdk stays 35.
> - **`mockito` is NOT yet a dependency** — it is Tier-2-only and lands with the spike, keeping
>   Tier 1's footprint to the Robolectric+coroutines-test set above.
>
> Iteration-2 refinements (carried forward, all 5 lenses APPROVE): `mockito-core 5.x` not
> `mockito-inline`; the spike's real risk is the inline mock-maker self-attaching on JDK 21;
> distance-SUM is a DAO test with per-row circumference; the happy-path spike is slimmed to its
> load-bearing claim (CONNECTED + recordSuccess + the negative not-CONNECTED-until-SUCCESS
> checkpoint) and the brittle GATT-protocol `inOrder` is dropped; the empty-session NULL→0.0
> coalesce gets an assertion; fixtures pin the existing 7-byte/0x01 `wheelPacket` helper; the
> reconnect-count-jump is promoted to a kept Tier-2 test.
>
> The goal is to cover integration logic the current pure-unit suite can't reach —
> chiefly the Room persistence path and the riskiest, previously-buggy parts of the BLE
> connection state machine — on the JVM, without a device or emulator.

## 1. Goal and non-goals

**Goal:** add JVM tests for the two highest-risk, currently-untested areas:
1. The Room persistence path in `BikeRepository`/DAO (the NaN→NULL heading rule,
   session resume + odometer-seed continuity, distance SUM, CSV export).
2. The riskiest parts of the `CscBleDataSource` connection state machine (false-CONNECTED
   on CCCD-write failure, the two stale-callback CAS gates, null `connectGatt` backoff).

**Non-goals (explicitly out of scope):**
- The firmware (compiled in CI via arduino-cli; not functionally tested here).
- A real BLE radio / a real sensor / a simulator (SETTLED: no simulator).
- Compose UI tests.
- The destructive Room migration itself (no migration code; `exportSchema = false`, so
  room-testing's `MigrationTestHelper` is unusable and unneeded — schema change recreates
  the table by design). SETTLED: destructive migration stays.
- Re-testing logic the pure-unit suite already locks: backoff math
  (`ReconnectPolicyTest`/`CscBleBackoffTest`), the decoder
  (`CscMeasurementDecoderTest`), the DIS/feature parsers
  (`FirmwareRevisionParseTest`/`CscFeatureParseTest`), and the heading/NaN behavior
  (`HeadingTest`). Tier 2 must NOT re-verify these through a GATT mock.

## 2. Approach — DECIDED: C (hybrid), gated behind a feasibility spike

**Decision: Approach C** — Robolectric supplies the runtime so framework classes resolve;
Mockito 5.x's **default inline mock-maker** mocks the `final` BLE classes
(`BluetoothDevice`, `BluetoothGatt`, `BluetoothGattCharacteristic`,
`BluetoothGattDescriptor`); the test captures the `BluetoothGattCallback` (the
`SensorConnection`) passed to `connectGatt` via an `ArgumentCaptor` and drives its callbacks
directly. (Use `mockito-core:5.x` — NOT the deprecated `mockito-inline` shim, see Section 3.)

- **Reject A (pure shadows):** Robolectric's `ShadowBluetoothLeScanner` exposes no public
  API to inject a scan result, and shadows can't auto-drive the GATT sequence or make
  `connectGatt` return `null`. Not implementable for the scenarios that matter.
- **Reject B (extract a `BleClient` seam):** rewrites recently-hardened ownership/CAS
  concurrency code purely to test it. The captured-callback technique gets ~95% of B's
  value at a fraction of the churn. Unanimous across lenses; do NOT revisit this iteration.
- **The simplicity caveat, honored:** the two CAS gates ARE pure `AtomicReference`
  identity logic. But they live in the *private inner* `SensorConnection`, unreachable
  without C's captured callback (or the rejected B refactor). So C is the minimal way in.
  In exchange, Tier 2 scope is cut hard (Section 4) so C's mock surface stays small.
- **Abort condition (no thrash toward the rejected B):** if the spike shows the inline
  mock-maker cannot mock the `final` `android.bluetooth.*` classes AT ALL under JDK 21 (not
  merely that `ScanResult` resists construction — that has the 2a hook as a fallback), then
  Tier 2 is ABANDONED, not rescued by Approach B. The CAS gates stay covered by code review +
  the existing `ReconnectPolicy`/decoder unit tests, and all effort goes to Tier 1 + a one-off
  manual smoke. Naming the abort up front stops a feasibility failure from tempting the very
  `BleClient` refactor this plan rejected.

### 2a. The one load-bearing seam (gating dependency for ALL of Tier 2)

`connectGatt` (line 322) is reached ONLY through the private `scanCallback`
(`devices[address]` is populated only at line 290). No scan result → no `connectGatt` →
no capturable callback → nothing in Tier 2 is reachable. This is the FIRST thing to solve.

**Decision:** add exactly ONE `@VisibleForTesting` hook that feeds the source's own
private `scanCallback`, handing it a Mockito (`mockito-core` inline mock-maker)
`BluetoothDevice` mock (NOT a shadow device) — the same handle must later be stubbed to
return `null` (zombie test) or a mock
`BluetoothGatt` (happy path). To sidestep awkward `ScanResult`/`ScanRecord` construction,
the hook takes the device + name + rssi directly:

```kotlin
@VisibleForTesting
internal fun onScanResultForTest(device: BluetoothDevice, name: String, rssi: Int) { ... }
```

(Equivalent alternative: capture the `ScanCallback` passed to `scanner.startScan` via a
Mockito captor on a mocked `BluetoothLeScanner`. Prefer whichever the spike proves
cheaper; the captor avoids a production change but needs a real-enough `ScanResult`.)

### 2b. Coroutine determinism — narrowed, deferred behind the spike

`scope = CoroutineScope(SupervisorJob())` (line 85) is hard-coded; the stale watcher
(`delay(1000)`, line 426) and heading ticker (`delay(250)`, line 447) run on it.

**Decision:** do NOT inject a dispatcher wholesale. Nearly every Tier-2 path
(CCCD failure, the two CAS gates, null `connectGatt`) is driven synchronously through the
captured callback and needs NO virtual time. Dispatcher injection earns its keep for
exactly ONE optional test (speed zeroes after `STALE_MS` idle) and the paired-change path.
If implemented, it is a single `@VisibleForTesting` constructor param defaulting to a real
scope; the stale-watcher test pairs `StandardTestDispatcher` with Robolectric
`ShadowSystemClock` (the watcher reads `SystemClock.elapsedRealtime()` at line 430 — a
separate clock axis that must be advanced in tandem, which is why this test is rated
low-priority). Heading-ticker zeroing is already covered by `HeadingTest`; do not retest.

**Determinism caveat for the SYNCHRONOUS Tier-2 tests:** `handleMeasurement` itself reads
`SystemClock.elapsedRealtime()` three times (~lines 614/644/649) and passes it into
`decoder.decode(...)`. Under Robolectric the default `ShadowSystemClock` starts at 0, so two
notifications driven at the same shadow instant give dTicks=0 and a degenerate speed. The
kept synchronous tests are therefore clock-free ONLY because they assert on
count/delta/emission or on non-advance (the CAS gates assert emptiness) — NOT on derived
speed. If any measurement-driving test ever asserts a speed value, it must advance
`ShadowSystemClock` between notifications. Keep the kept tests off derived speed.

## 3. Infrastructure

- Test deps (test scope): `robolectric`, **`org.mockito:mockito-core:5.x`** (NOT
  `mockito-inline`) + `org.mockito.kotlin:mockito-kotlin:5.x`, `kotlinx-coroutines-test`.
  Since Mockito 5 the inline mock-maker that mocks `final` classes is the **default** in
  `mockito-core`; the separate `mockito-inline` artifact is a deprecated, effectively-empty
  shim slated for removal — depending on it invites a version-conflict / no-op. Nothing extra
  is needed to mock the `final` BLE classes. NOT `androidx.room:room-testing` —
  `Room.inMemoryDatabaseBuilder` needs only the existing `room-runtime`/`room-ktx` plus a
  Robolectric `Context`; the migration helper is unusable under `exportSchema = false`.
- **JDK-21 inline mock-maker self-attach (the spike's real go/no-go):** on newer JDKs Byte
  Buddy emits "self-attaching has been disabled / will fail in a future release" and can
  fail depending on JVM flags. Pre-empt it: add `-XX:+EnableDynamicAgentLoading` to the test
  JVM args (`testOptions { unitTests.all { it.jvmArgs("-XX:+EnableDynamicAgentLoading") } }`),
  and if still flaky pin the mock-maker via a `org.mockito.plugins.MockMaker` resource set to
  `mock-maker-inline`. This — not "does Robolectric run" — is what the spike must confirm.
- `build.gradle.kts`: `testOptions { unitTests.isIncludeAndroidResources = true }`.
- Pin `@Config(sdk = [34])` on every Robolectric test so the PRODUCTION `Build.VERSION.SDK_INT`
  branches execute (notably the TIRAMISU+ `writeDescriptor(cccd, value)` path at ~506-513).
  It does NOT choose which callback overload fires — the TEST drives the callbacks directly,
  so the author invokes the API-34 `ByteArray` overloads of
  `onCharacteristicRead`/`onCharacteristicChanged` deliberately (the deprecated overload reads
  `characteristic.value`, which is `null` on a Mockito mock and would feed null to the
  decoder). Pinning sdk=34 also keeps exactly one `android-all` jar downloading. Cache that
  jar in `android.yml` (CI runs JDK 21, `--no-daemon`) so first runs don't re-download.
- Keep the existing pure-logic tests exactly as-is (fast, no Robolectric runner).
- **Build requirement — writable SDK overlay (clean-checkout gotcha).** `robolectric.properties`
  `sdk=34` makes AGP provision `build-tools;34.0.0`. On the t480 the Android SDK comes from the
  read-only Nix store, so a clean run fails trying to write into it. Run the suite with a writable
  overlay: `ANDROID_SDK_ROOT=$HOME/.android-sdk-writable ANDROID_HOME=$HOME/.android-sdk-writable
  ./gradlew testDebugUnitTest --no-daemon --console=plain` (or add `build-tools;34.0.0` to
  `hosts/t480/default.nix`). Without it the failure is an AGP provisioning error, not a test failure.

## 4. Test targets (scope DECIDED)

### Tier 1 — Room persistence (LANDED, iteration 3 — 8 tests, green)
Implemented in `app/src/test/java/com/roundearth/bikecomputer/data/BikePersistenceTest.kt`
with `FakeBikeDataSource.kt`. Covers: NaN→NULL through `start()` (no-throw INSERT + NULL
read-back), known-heading round-trip, NaN→empty-CSV-field, session resume + seed continuity,
fresh-session 0.0 seed, empty-session NULL→0.0 coalesce, per-row distance SUM (d1*c1+d2*c2 with
DIFFERENT circumferences + a zero-delta row), and a high-magnitude row (cumulative revs near
`0xFFFFFFFF`, event time at the 16-bit ceiling) round-tripping without Int truncation — the only
place the suite touches the realistic large-magnitude steady state of a long-lived sensor.
The design notes below are retained as the rationale of record.

In-memory Room (`Room.inMemoryDatabaseBuilder`) under Robolectric. **Tier 1 carries none of
Tier 2's infra/spike risk:** it pulls in only `robolectric` + `kotlinx-coroutines-test`
(`mockito-core` and the `@VisibleForTesting` scan hook are Tier-2-ONLY). The fake source is
trivial — `BikeDataSource.seedOdometer` is an interface default, so it is a ~15-line class
with a `revolutionReadings` channel/flow and a captured `seedOdometer` arg, not a Mockito
mock. Sequence Tier 1 to merge BEFORE the Tier-2 spike: it de-risks the whole effort and is
the ONLY way to lock the NaN→NULL regression, so even if the spike is ever cut, Tier 1 alone
is a complete, defensible win. `resolveSessionId`/`System.currentTimeMillis()` (line 93) is
real-time, so assert with relative windows.

- **NaN heading → NULL (THE regression to lock).** The crash was at INSERT (binding NaN
  into a NOT NULL column); the fix is `BikeRepository` lines 73-74
  (`reading.headingDegrees.takeUnless { it.isNaN() }` — anchor on this *symbol*, not the
  line number). Drive a `WheelRevolutionReading` with `headingDegrees = Float.NaN`
  **through the repository's persist path** (fake source emits it; in-memory Room), then
  assert (a) the real INSERT **completes without the original constraint crash**, (b) the
  stored row reads back `headingDegrees IS NULL`, (c) `exportCsvTo` yields an EMPTY field
  (not "NaN"/"0.0"), and (d) a real heading round-trips. NOTE: the `takeUnless { isNaN() }`
  mapping is Kotlin and IS visible to a plain captured-row test; what the through-repo +
  in-memory-Room path adds that a captured-row test can't is exactly (a) the no-throw
  INSERT and (c)/(b) the NULL read-back/empty-CSV end-to-end. Don't over-claim "pure-JVM
  can't see the mapping" — it can; Room locks the crash-and-round-trip half.
- **Session resume + odometer-seed continuity (ONE coupled invariant, incl. the empty-session
  NULL coalesce).** Within the 30-min window: `resolveSessionId` resumes the prior session AND
  `source.seedOdometer` receives that session's prior non-NULL `sessionDistanceMeters`.
  Outside the window / new session: a fresh id AND `seedOdometer(0.0)`. The new-session 0.0 is
  itself the **NULL→0.0 coalesce at `BikeRepository:60`** (`dao.sessionDistanceMeters(id) ?: 0.0`):
  `SUM` over zero matching rows returns NULL (`sessionDistanceMeters` is `Double?`), so first
  assert `dao.sessionDistanceMeters(<unused id>) == null`, THEN assert the repo seeds
  `seedOdometer(0.0)` from it — locking both the SUM-over-empty NULL and the `?: 0.0` guard
  that makes a fresh ride start at 0 instead of NPEing/carrying garbage. Assert the join, not
  the halves separately (a resume that zeroes distance is a real user-visible bug).
- **Distance SUM — a DAO/`seedOdometer` test, NOT a `BikeRepository` computation.** The math
  lives entirely in the DAO query `SELECT SUM(deltaRevolutions * wheelCircumferenceM) ...`
  (`RevolutionEventDao:45`) — the multiply is **INSIDE the sum, per row** — and the repo only
  consumes it at line 60. So assert the SUM directly against the DAO on the in-memory DB
  (cheap, deterministic), not through a full recording session. The fixture MUST: (1) include
  a `deltaRevolutions > 1` row (a coalesced/post-drop advance — the firmware ring buffer can
  drop individual timestamps under loop() starvation while the cumulative count stays correct,
  so the next delivered packet legitimately carries delta>1) and a zero-delta row (a segment
  baseline OR reboot — INDISTINGUISHABLE at the DAO layer; do not author a reboot-specific DAO
  fixture), asserting the zero-delta row contributes 0 and the >1 row its full multiple; and
  (2) use at least TWO rows with DIFFERENT `wheelCircumferenceM`, asserting the result equals
  the per-row `d1*c1 + d2*c2` and NOT `(d1+d2)*c` — the ONLY way to pin the in-sum multiply
  against a "simplification" that hoists it outside the SUM.
- **CSV export (trimmed to NON-overlapping assertions):** assert header text + column order +
  the `cumulative_event_time_1024` column present and correct, with ONE fully-populated known
  row round-tripping — so a future column reorder/rename is caught. Do NOT re-assert the
  empty-heading field here; the NaN→NULL test already owns that, and overlapping the two
  export assertions buys nothing.

### Tier 2 — connection state machine (PRUNED to six previously-buggy paths)
**Precondition:** write the happy-path scenario FIRST as a spike that proves the trifecta
(`RobolectricTestRunner` + Mockito's inline mock-maker on a `final` `BluetoothDevice` +
`ArgumentCaptor` on the callback + driving `onConnectionStateChange`). Only fan out the rest
if it passes; if the inline mock-maker can't mock the `final` BLE classes at ALL, INVOKE THE
ABORT (Section 2 — Tier 2 is dropped, not rescued by B); if only `ScanResult` resists
construction, use the direct-device `@VisibleForTesting` hook (2a).

> Each Tier-2 test constructs a **fresh `CscBleDataSource`** (cheap; no shared singleton in
> tests). This matters: `_readings` is a `Channel(UNLIMITED)`, and `seen`/`devices` are
> instance `ConcurrentHashMap`s that `stop()` does NOT clear — so the "revolutionReadings
> stays empty" and "`connection.get()` is still B" assertions are deterministic only with a
> fresh instance per test. `stop()` in `@After` is purely job-leak hygiene
> (`staleJob`/`headingJob`/`pairedJob`), not a state reset.

1. **Happy path → CONNECTED (the spike) — SLIMMED to its load-bearing claims.** Inject scan
   result for the paired address → `connectGatt` called → drive `STATE_CONNECTED` →
   `onServicesDiscovered` → `onDescriptorWrite(SUCCESS)`. Assert:
   (a) an explicit NEGATIVE checkpoint **AFTER `onServicesDiscovered` but BEFORE
   `onDescriptorWrite`**: `_connectionState.value != CONNECTED` AND
   `verify(reconnect, never()).recordSuccess(any())` — this is the discriminating assertion
   that proves the `ready`/CONNECTED gate (the test supplies the ordering, so a positive-only
   sequence would pass even with a broken gate); (b) after `onDescriptorWrite(SUCCESS)`,
   `connectionState` becomes CONNECTED and `recordSuccess` fired (clean subscribe → no
   penalty). DROP the brittle `inOrder(setCharacteristicNotification before writeDescriptor)`
   and the "firmware-read-after-Feature-read" chaining assertions — those pin Android's GATT
   protocol shape (not this app's observable behavior; they break on any reasonable
   subscribe-helper refactor) and re-test parser chaining that
   `CscFeatureParseTest`/`FirmwareRevisionParseTest` already own. Still drive the **API-34
   `ByteArray` overloads** of `onCharacteristicRead`/`onCharacteristicChanged` and assert the
   bytes reach the decoder (see Section 7 for the exact fixture shape).
2. **CCCD write fails → no false CONNECTED AND backoff armed (TWO-STEP).** `onDescriptorWrite`
   failure only calls `g.disconnect()` and returns (lines 517-522) — it does NOT arm
   backoff. Backoff is armed only by the follow-on `STATE_DISCONNECTED` with `wasReady=false`
   (lines 480-492). So: drive `onDescriptorWrite(GATT_FAILURE)`, THEN drive
   `onConnectionStateChange(g, status = GATT_SUCCESS, newState = STATE_DISCONNECTED)` — a
   LOCAL `disconnect()` reports status 0, NOT 133; do NOT author a status=133 here, it would
   misrepresent a clean local teardown as a flap. The flap-ness comes solely from
   `wasReady == false`; the production branch (478-494) ignores `status` entirely. Assert
   `connectionState` never became CONNECTED AND `reconnect.canAttempt(address)` is now false
   / `failures(address) == 1`.
3. **Stale-callback CAS — onConnectionStateChange gate (the `compareAndSet(this, null)` gate,
   ~line 486).** Open conn A; fast-flap so conn B owns the slot; fire conn A's
   `onConnectionStateChange(STATE_DISCONNECTED)`. Assert `connection.get()` is still B,
   `markConnected(false)` did NOT fire for B's address, and `reconnect.failures(B) == 0` (the
   late old-gatt drop must not evict or penalize B).
4. **Stale-measurement CAS — handleMeasurement gate (`if (!ready || connection.get() !== this)`,
   ~line 612), the data-corruption one.** The gate has TWO conditions; exercise the
   `connection.get() !== this` branch DELIBERATELY (not the `!ready` short-circuit): set conn B
   ready and owning the slot, leave conn A with `ready == true` from before its teardown, then
   deliver `onCharacteristicChanged` on A's gatt. Assert NO `WheelRevolutionReading` is emitted
   (`_readings` stays empty) AND the live odometer/sensor distance did NOT advance (snapshot
   `source.data.odometerKm` before/after) — the gate guards BOTH the lossless `_readings`
   channel sink and the under-`stateLock` odometer/speed sink (~637-653), so a future edit that
   moved the gate past one sink but not the other must be caught.
5. **null `connectGatt` → backoff armed synchronously (line 323-332).** Stub the device's
   `connectGatt` to return `null`; assert `connection.get() == null` (slot cleared, no
   zombie) and `reconnect.failures(address) == 1`. This is the ONLY path that arms backoff
   inline with no callback — and only reachable because the device is a controllable mock.
6. **Cumulative-count JUMP across reconnect → absorbed as deltaRevs=0 (PROMOTED from optional
   to a kept test — cheap once the harness exists, otherwise unprotected).** Drive a packet at
   `revs=N`, tear down conn A, reconnect (new `SensorConnection` → new `CscMeasurementDecoder`,
   `haveWheel=false`), then deliver `revs=N+50`. Assert the emitted reading has
   `deltaRevolutions == 0`, `distanceMeters == 0`, `speedKph == null`. The guard works because
   each reconnect constructs a FRESH decoder (`private val decoder` per `SensorConnection`,
   ~line 470) whose `haveWheel == false` puts the first packet on the baseline branch (delta 0)
   — NOT the reboot-detection path. This locks the per-connection-decoder invariant three
   production comments depend on: the failure mode being guarded is a future refactor that
   reuses the decoder or seeds `lastWheelRevs` from the DB, which would land the +50 in the
   in-range advance branch (`1 until 0x8000_0000`) and count 50 phantom revolutions
   (~105 m on a 2.1 m wheel). Reuse the same `wheelPacket(revs, time)` numbers as the existing
   `monotonicEventTimeEstimatesARebootGapAndStaysMonotonic` decoder test.

**DROPPED from the earlier draft (covered elsewhere or low value for a solo app):**
- Standalone *flap-before-subscribe* and *healthy-drop-no-penalty* backoff tests — the
  `wasReady → recordFailure/recordSuccess` RULES (the policy arithmetic) are already locked by
  `ReconnectPolicyTest`/`CscBleBackoffTest` with an injected clock; re-driving the rules through a
  full GATT mock is duplicate coverage with more brittleness. NOTE the WIRING is NOT redundant: the
  step-2 (`cccdWriteFailure`) and step-5 (`nullConnectGatt`) tests are the SOLE guards that a live
  disconnect / null-`connectGatt` actually drives production into `reconnect.onDisconnect`/
  `recordFailure` — `CscBleBackoffTest` only tests the pure `backoffDelayMs()` math and never drives
  the GATT callback. Do not delete steps 2/5 believing them redundant.
- *Paired-sensor change* end-to-end — depends on the paired-flow collection on the internal
  scope, adding the dispatcher injection for low marginal value. Reconsider only if the dispatcher
  seam lands cleanly for the stale-watcher test anyway.
  (*Adapter toggle* was formerly lumped here as "low value" — that was misleading; it is the
  newest hardened code and is now recorded as a NAMED accepted gap in §4a below, not a low-value
  omission.)
- *Feature/firmware parse assertions* beyond the chaining already asserted in step 1 — parse
  logic is `CscFeatureParseTest`/`FirmwareRevisionParseTest`'s job.
- The standalone *stale-speed-zeroing* timer test — optional, low priority, behind the
  dispatcher seam (2b); do it last if at all.

### 4a. Accepted coverage gaps (NAMED, not low-value-by-omission)

These are conscious skips a future reader can find — not silent omissions. Each is here because
covering it costs more than it is worth for a solo app, NOT because the code is low-risk.

- **Adapter-toggle recovery — UNCOVERED, highest-residual-risk gap.** `onAdapterOff()` +
  `btStateReceiver` + the `STATE_ON → reconnect.clear() + startScan()` revival (CscBleDataSource.kt
  ~204-259, commit e0c7a7a, the NEWEST hardened code) has zero coverage. It differs from the
  covered disconnect path in two non-obvious ways the CAS tests do NOT transfer to: (1) `onAdapterOff`
  evicts the slot UNCONDITIONALLY via `connection.getAndSet(null)`, NOT the `compareAndSet(this,null)`
  identity gate; (2) the `STATE_ON` handler is the only path that drops accumulated backoff on a
  deliberate toggle. Covering it needs `ShadowApplication` broadcast plumbing (or a new
  `onAdapterOffForTest`/`onAdapterOnForTest` seam). Deliberately deferred. If ever covered, prefer a
  thin seam over `ShadowApplication` and assert the observable outcome: slot == null, `gatt.close()`
  fired, `seen[addr].connected == false`, and a post-toggle sighting reconnects (`reconnect.clear`
  ran so `failures(addr) == 0`).
- **onCharacteristicRead read chain (Feature → DIS) — uncovered by choice.** Production chains the
  CSC Feature read then the DIS firmware read under the one-GATT-op-in-flight rule. `stubCscService()`
  leaves Feature/DIS unstubbed, so `startRead()` returns false and the chain never fires under test
  (parsers are owned by `CscFeatureParseTest`/`FirmwareRevisionParseTest`). The platform-specific
  read SERIALIZATION is therefore unasserted. Deferred for a solo app.
- **Deprecated pre-API-33 `onCharacteristicRead/Changed` 2-arg overloads — uncovered by choice.**
  Tests pin `sdk=34` and drive only the API-34 `ByteArray` overloads; the deprecated `c.value` path
  is not exercised. Documented so a refactor routing through it isn't mistaken for covered.
- **Non-success `onServicesDiscovered` (status 129) — uncovered by choice.** Production ignores the
  `status` param and relies on the null-characteristic guard; tests drive only `GATT_SUCCESS`. See
  also the §7 seed.
- **`onServicesDiscovered` with NO CSC measurement characteristic — a real recovery hole, UNCOVERED
  and unfixed.** If the service/characteristic is absent (partial GATT cache, a sensor mid-reflash
  advertising CSC but exposing no measurement char, an incomplete service-discovery table), the
  null-characteristic branch (CscBleDataSource ~521-528) logs and returns with NO `g.disconnect()`
  and NO backoff. The `SensorConnection` stays in the slot with `ready=false` forever, so every
  later scan sighting short-circuits on the non-null slot and the app NEVER retries — the only escape
  is a stack-initiated DISCONNECTED. Contrast the CCCD-failure path, which deliberately calls
  `g.disconnect()` to drop into rescan. NAMED here rather than fixed because the artifact under review
  is the test suite and altering BLE recovery logic is out of scope; a follow-up production hardening
  (call `g.disconnect()`/`teardown(this)` on that branch) plus a Tier-2 test (drive
  `onServicesDiscovered` with `stubCscService()` NOT called, assert slot freed + a follow-on sighting
  reconnects) is the recommended next step. The harness has every primitive needed.
- **`connectIfNeeded` post-publish ownership-race close — UNCOVERED, the file's most intricate
  invariant.** Lines ~357-362 close the GATT handle themselves when a teardown wins the slot in the
  window between `compareAndSet(null,conn)` and `conn.gatt=gatt` (else one of Android's scarce per-app
  GATT clients leaks). It is a genuine two-thread race: single-threaded callback driving always finds
  an uncontended slot, so `gatt.close()` at line 362 never fires under test. Deliberately documented,
  not tested — building a deterministic interleaving harness for one race is not worth it for a solo
  app.
- **In-connection reboot (backward count jump on the SAME live `SensorConnection`) — NOT
  APPLICABLE BY PHYSICS, decoder-level only.** Every counter reset on the ESP32-C6
  (`ESP_RST_BROWNOUT`/`ESP_RST_TASK_WDT`/`ESP_RST_PANIC`, the watchdog) is a FULL chip reset that
  reinitializes the BLE stack in `setup()`, so a counter reset ALWAYS coincides with a GATT teardown
  and a fresh per-connection decoder (`haveWheel=false`). A backward jump on a surviving connection
  is unreachable on this hardware — so it is correctly covered only at the decoder level
  (`CscMeasurementDecoderTest`) as defense-in-depth, and a future contributor must NOT author an
  unfaithful live-connection test feeding a backward jump through `onCharacteristicChanged` on the
  same callback. (A delta>1 first-post-reconnect packet from the mid-drain disconnect discard at
  `speed.ino:235-253` is behaviorally identical to delta 0 at the DAO and is intentionally not
  separately asserted.)

### Tier 3 — DROPPED
The end-to-end scan→connect→subscribe→notify→DAO thread duplicates Tier 1 + Tier 2's
step 1/4 coverage while adding the most mock surface for the least incremental insight.
Cut it. (The cumulative-count JUMP across reconnect — formerly the suggested end-to-end slot
— is now a kept Tier-2 test, step 6, driven through the same captured-callback harness.)

## 5. What success looks like

- Tier 1 + the kept Tier-2 paths (steps 1-6: happy-path spike, CCCD-fail two-step, both CAS
  gates, null-`connectGatt`, reconnect-count-jump) implemented; `./gradlew testDebugUnitTest`
  green and deterministic (no real-time `delay` flakiness — synchronous callback-driven tests
  use no virtual time; measurement-driving tests assert on count/delta/emission/non-advance,
  not derived speed; the optional stale-watcher test uses `StandardTestDispatcher` +
  `ShadowSystemClock`).
- Each Tier-2 test constructs a FRESH `CscBleDataSource` (so the `Channel(UNLIMITED)` and the
  `seen`/`devices` maps `stop()` doesn't clear can't leak across tests), and calls `stop()` in
  teardown to cancel `staleJob`/`headingJob`/`pairedJob` (the documented single teardown, lines
  190-200) purely as job-leak hygiene.
- At most a **minimal** production change: one `@VisibleForTesting` scan hook, and
  optionally one `@VisibleForTesting` dispatcher/scope constructor default — NO rewrite of
  the reviewed BLE/CAS logic.
- Every previously-buggy path has an ASSERTING (not merely exercising) test: false-CONNECTED
  on CCCD failure + the armed backoff, BOTH CAS gates, null-`connectGatt` backoff, and
  NaN→NULL through the repository.

## 6. Open questions — RESOLVED

1. ~~A vs B vs C~~ → **C**, gated behind a happy-path spike (Section 2).
2. ~~Dispatcher injection worth it?~~ → **Narrowly yes, deferred:** only for the optional
   stale-watcher test and (if ever added) the paired-change path; NOT wholesale (2b).
3. ~~Which Tier-2 scenarios earn their keep?~~ → **Six** (Section 4): happy-path spike
   (slimmed), CCCD-fail two-step, both CAS gates, null-`connectGatt`, and the
   reconnect-count-jump (promoted from optional). The rest are dropped as already-covered or
   low-value.
4. ~~Does this argue for the `BleClient` refactor?~~ → **No** (rejected, Section 2).

## 7. Seeds for the implementation PR (verify, don't re-decide)

- **Pin every CSC fixture to the existing `wheelPacket(revs, time)` helper** (the exact 7-byte,
  `flags=0x01`, wheel-only packet this firmware ALWAYS emits — the crank bit is never set;
  `CscMeasurementDecoderTest.kt:20-28` is the single source of truth). Both the Tier-1
  persistence fixtures and the Tier-2 notification→reading test hand raw bytes straight to the
  decoder via the API-34 `onCharacteristicChanged` `ByteArray` overload, so do NOT author
  crank-present or alternate-length packets this peripheral can never produce.
- **NON-GOAL — no keepalive/coasting fixture.** This firmware sends NOTHING while the wheel is
  still (one notify per accepted falling edge, inside the ring-buffer drain). A parked bike is
  modeled by SILENCE — the optional stale-watcher test (speed zeroes after `STALE_MS` idle) —
  NOT by repeated same-revs/advancing-time packets. The decoder's defensive same-revs handling
  is already unit-covered (`coastingSameRevsGivesNoSpeed`); don't add a coasting fixture to the
  BLE-layer suite.
- **Cumulative-count JUMP across reconnect** is now Tier-2 step 6 (kept). The mechanism is the
  fresh per-connection decoder's `haveWheel == false` BASELINE branch (delta 0), NOT the
  reboot-detection path (`raw >= 0x8000_0000`). Reuse the same numbers as the existing
  `monotonicEventTimeEstimatesARebootGapAndStaysMonotonic` decoder test.
- **Realistic reboot fixture (sharpened):** event time is derived PURELY from `millis()`
  (`eventTime = (uint16_t)((now*1024)/1000)`, no firmware-maintained monotonic counter), so a
  brownout/watchdog that restarts `millis()` makes the post-reboot event time small-but-NONZERO
  (the first post-boot revolution's `now` — a few hundred to a few thousand ticks) and
  UNCORRELATED with the pre-reboot value. The 16-bit event-time delta is therefore a meaningless
  backward/aliased number that MUST be discarded in favor of the wall-clock estimate. Do NOT
  author event-time=0 (it could make dTicks land on a benign value by accident). The existing
  `monotonicEventTimeEstimatesARebootGapAndStaysMonotonic` test is the CANONICAL shape — revs
  jump `1_000_000 → 5`, event time `1000 → 2000`, a 5 s wall-clock gap, accumulator advances by
  `5_000*1024/1000` not the raw backward delta — reuse its numbers.
- **Debounce ceiling:** firmware enforces `MIN_MS=60` between edges (~61 event-time ticks,
  ~125 km/h on a 2.1 m wheel). Keep happy-path fixture event-time deltas ≥ ~61 ticks so they
  encode physically possible speeds.
- **`onServicesDiscovered` ignores its `status` param** (conscious omission, not coverage): a
  failed discovery (status != SUCCESS) still calls `g.getService(...)`, gets null, logs, and
  returns — leaving the link HALF-OPEN with no backoff armed and no disconnect (unlike the CCCD
  path). Untested by choice for a solo app (continuous scan eventually retries), recorded here
  so it isn't mistaken for covered.
- Confirm the spike (Section 4, step 1) proves the **inline mock-maker self-attaches cleanly
  under the CI JVM (JDK 21)** with `-XX:+EnableDynamicAgentLoading` — this, not "Robolectric
  runs," is the go/no-go signal and the most common "works on my machine" breakage for this
  kind of suite. (The `ArgumentCaptor<BluetoothGattCallback>` technique itself is trivial.)

### 7a. Tier-2 spike checklist (iteration-3 review — close these or the spike stalls/passes vacuously)

These are the load-bearing fixture details the iteration-3 panel surfaced. The Tier-1 landing
this iteration does not exercise any of them; they are the gate for the NEXT iteration's spike.

- **pairedAddress is the REAL entry gate, not `connectGatt` (test-infra BLOCKER).** Every route
  to `connectGatt` runs through `connectIfNeeded(address)`, reached only when
  `address == pairedAddress` (scan path, line 302) or via the `pairedSensor` collector (line 172).
  `pairedAddress` is `@Volatile`, assigned ONLY inside `pairedSensor.collect { ... }` on the
  uninjectable internal `CoroutineScope(SupervisorJob())` — so `MutableStateFlow(target)` collects
  ASYNCHRONOUSLY on `Dispatchers.Default`, which `shadowOf(getMainLooper()).idle()` will NOT flush.
  **Fix for the spike:** make the `@VisibleForTesting` hook call `connectIfNeeded(address)` DIRECTLY
  after `devices[address] = device`, bypassing the `== pairedAddress` check entirely (one extra
  line), and assert `verify(device).connectGatt(...)` was actually invoked before trusting any
  captor — a silent pairedAddress miss otherwise yields a green-but-vacuous test.
- **Stub `getUuid()` on EVERY characteristic mock, or the UUID guards make tests vacuously green
  (platform-faithfulness).** `onCharacteristicChanged`/`onCharacteristicRead` gate on
  `c.uuid == CSC_MEASUREMENT_UUID` / `CSC_FEATURE_UUID` / `FIRMWARE_REVISION_UUID` (lines ~596,
  601, 572-589). A Mockito mock returns `null` from `getUuid()` by default, so an unstubbed mock
  silently drops the measurement and the test "passes" exercising nothing. Stub the measurement
  UUID for steps 1 and 4; stub Feature + DIS-firmware UUIDs for the read chain.
- **The full `getService → getCharacteristic → getDescriptor` mock graph gates reaching
  `onDescriptorWrite` (co-equal spike risk with the inline-mock-maker self-attach).**
  `onServicesDiscovered` (498-514) early-`return`s if any link is null, so neither the negative
  checkpoint nor CONNECTED is reachable unless `gatt.getService(CSC_SERVICE_UUID) → mockService`,
  `mockService.getCharacteristic(CSC_MEASUREMENT_UUID) → mockChar`,
  `mockChar.getDescriptor(CCCD_UUID) → mockDescriptor` are all stubbed. Make the spike's pass
  criterion `verify(gatt).writeDescriptor(eq(descriptor), any())` BEFORE asserting CONNECTED; if
  any link in this final-class chain can't be stubbed, that is part of the same ABORT trigger as
  the self-attach failure. `writeDescriptor`'s return is ignored by production AND the test — the
  CCCD confirmation is delivered solely by the manual `onDescriptorWrite(SUCCESS)` callback; do
  not stub the return to auto-fire.
- **Step 4 MUST drive the API-34 `ByteArray` `onCharacteristicChanged` overload with a stubbed
  UUID,** or it passes for the WRONG reason: the deprecated overload reads `c.value` (null on a
  mock) and `handleMeasurement` returns at the null-bytes early-return BEFORE reaching the CAS
  gate at ~612, and an unstubbed UUID skips `handleMeasurement` entirely. Only the ByteArray
  overload + stubbed UUID reaches the ownership gate the test claims to lock.
- **Step 2 (CCCD-fail) needs `verify(gatt).disconnect()` to stop passing for the wrong reason
  (robustness).** The test itself supplies the follow-on `onConnectionStateChange(DISCONNECTED)`
  that arms backoff, so between `onDescriptorWrite(FAILURE)` and the manual disconnect callback,
  also assert `verify(gatt).disconnect()` — that proves PRODUCTION requested the teardown rather
  than the test papering it over with a hand-driven callback.
- **The `reconnect` field is `private` — Tier 2's "one seam" claim is incomplete (robustness).**
  Steps 2/3/5/6 read `reconnect.failures(addr)`/`canAttempt(addr)`, but the field at line ~124 is
  private. EITHER add a second `@VisibleForTesting` accessor
  (`internal fun backoffFailures(addr) = reconnect.failures(addr)`) — so the production surface is
  "one scan hook + one reconnect-state read" — OR drop the `failures` assertions and assert the
  OBSERVABLE consequence instead: a subsequent scan result for the same address does NOT re-invoke
  `connectGatt` until `canAttempt` clears (the real "don't hot-loop" invariant; needs no extra seam).
- **Step 6's reconnect-jump fixture must use a PRESERVED large count, not the reboot numbers
  (electronics).** A reconnect WITHOUT reboot (the common case: BLE drop / adapter toggle; the
  ESP32 re-advertises at `speed.ino:64` with the count UNCHANGED) preserves the cumulative count:
  connection A ends at `revs=N` (e.g. `1_000_000`), connection B's FIRST packet is `revs=N+50`
  (`1_000_050`, count PRESERVED and ADVANCED across the drop). Assert `deltaRevs==0` because B's
  fresh decoder has `haveWheel==false` (baseline branch), NOT because of backward-jump/reboot
  detection. The `1_000_000 → 5` BACKWARD numbers belong to the decoder-level REBOOT fixture only
  (a count that went DOWN is itself a reboot). Note that a delta>1 first-post-reconnect packet can
  also originate from the mid-drain disconnect discard (`speed.ino:235-253`), not just ring-buffer
  overflow — provenance so a future reader doesn't "fix" the baseline branch into counting it.
- **Extract `wheelPacket` into a shared test helper.** `CscMeasurementDecoderTest.kt:20-28`
  `wheelPacket` is `private`, so neither Tier-1 nor Tier-2 can reference it — the "single source of
  truth" claim is currently false. When Tier 2 lands, lift it to a top-level/internal helper (e.g.
  `CscTestFixtures.kt`) so the decoder test and the BLE test share one definition. (Tier 1 as
  landed builds `RevolutionEvent`s directly and needs no byte packets, so this is deferred to Tier 2.)

## 8. Implementation-PR gate (review the eventual code, not just this plan)

This iteration approves the approach and the pruned scope. The implementation PR must
confirm, as a coverage check, that these "exercised but not asserted" hazards each have an
asserting test: the **two-step** CCCD backoff (not stopping at `disconnect()`), the **two
distinct** CAS gates (`onConnectionStateChange` AND `handleMeasurement`), and the NaN→NULL
mapping driven **through `BikeRepository`** (not a pre-nulled row handed to the DAO).
