# P008a: Android API client (generated from OpenAPI) and network stack

| Field | Value |
| --- | --- |
| Prompt | P008 · Android API client, **part a** of two |
| Milestone | M2 (depends on P004, P005, P007; all merged) |
| Branch | `feat/008a-android-api-client` |
| PR title | `feat(android): OpenAPI-generated API client, auth interceptor and error mapping [P008a]` |
| Notion | [P008 row in the Prompt Log](https://app.notion.com/p/3eb07370772081a9ba01dbe7d82a17f7) |
| Date | 2026-10-03 |
| Plan refs | Plan v7 §5.1, §6.1–6.3, §12.2, §12.4, §17, §20; ADR 0004, ADR 0006, ADR 0008, ADR 0009 |

## 1. Objective

Give the Android app a typed API client that is **generated** from `contracts/openapi.json`
(contract 0.2.0: `getHealth`, `getReadiness`, `bootstrapMe`, `getMe`, `ProblemDetails`), and the
network layer around it: OkHttp and Retrofit, a token-provider seam with an auth interceptor and
an authenticator, safe retries, problem+json error mapping, and the base URL from a Gradle
property that is not in the repository.

**P008 is split in two because of its size** (the prompt's §16 asks for this above ~800 lines):

- **P008a (this pull request):** R0 spike, R1 generation, R2 base URL, R3 token seam, R4 OkHttp
  stack, R5 Retrofit, R6 error mapping, their tests; and the documents that describe exactly
  this code (ADR 0009, the README section, three rules in `CLAUDE.md`, the diagram).
- **P008b (next, after this is merged):** R7 the debug-only connectivity check screen with its
  strings and tests, R8 the CI changes (`contracts/**` path filter, `assembleRelease`).

Not in scope, and not built: Firebase, sign-in, DataStore, Room, WorkManager, location, maps,
push, certificate pinning, caching, analytics, any backend or contract change.

## 2. Context & prerequisites

- P007b merged (PR #15, `82b94c3`). No open pull requests. Hooks active.
- **The working tree was not clean at `/start-prompt`,** as in P007b: `android/gradlew.bat`
  still has the uncommitted change that Claude Code did not make. It was left untouched and is
  in no commit of this branch. Rahul decides what to do with it (section 11).
- The base URL: Rahul keeps `saferoute.apiBaseUrl` in his user-level Gradle properties. Claude
  Code did not read that file, and no build output containing the value was printed or copied.
- No phone or emulator: everything here is verified with JVM tests.

## 3. Workflow executed

1. `/start-prompt P008`: `main` fast-forwarded to `82b94c3`; Notion P007b and the P007 umbrella
   row → Merged, P008 → In progress; branch created (renamed to `feat/008a-…` when the split was
   decided).
2. Looked up current stable versions on Maven Central and the Gradle Plugin Portal; read the
   generator's option list and the plugin's task properties at the `v7.25.0` tag.
3. **R0 spike** (section 7). Result: the generator handles the 3.1 contract. Commit `56a5205`.
4. **R1** generation wired into the build, dependencies, `INTERNET`, network security config.
   Commit `7c7a8ef`.
5. **R2** base URL (`5497257`), **R3** token seam (`e917832`), **R4** interceptors,
   authenticator, retry (`c8a2451`), **R5 + R6** Retrofit, Hilt, error mapping (`d36782e`).
6. Tests (`9159a68`). One test failed on the way (section 6).
7. ADR 0009, diagram, README, third-party list, `CLAUDE.md` rules, this log; quality gates;
   `/ship-prompt`.

## 4. Changes

| Area | What |
| --- | --- |
| Build | `generateApiClient` (OpenAPI Generator 7.25.0) → `app/build/generated/openapi`, registered as a generated source folder of every variant; `BuildConfig.API_BASE_URL` and `API_BASE_URL_CONFIGURED` from `saferoute.apiBaseUrl`; `checkReleaseApiBaseUrl` before every release build |
| Catalog | OkHttp 5.5.0, Retrofit 3.0.0 + its kotlinx-serialization converter 3.0.0, kotlinx-serialization-json 1.11.0, MockWebServer (`mockwebserver3`) 5.5.0, plugin `org.openapi.generator` 7.25.0. coroutines-test was already there |
| Manifest | `INTERNET` (the first and only permission); `networkSecurityConfig` |
| `res/xml/network_security_config.xml` | cleartext refused for every host; system certificate authorities only; no debug overrides |
| `core/network` | `ApiConfig` and the base URL rule; `networkJson` |
| `core/network/auth` | `IdTokenProvider`, `SignedOutIdTokenProvider`, `AuthInterceptor`, `TokenAuthenticator` |
| `core/network/interceptor` | `RequestIdInterceptor`, `UserAgentInterceptor`, `SafeLoggingInterceptor` (debug only) |
| `core/network/retry` | `RetryInterceptor`, `Sleeper` |
| `core/network/errors` | `ApiFailure`, `ApiResult`, `FieldError`, `ProblemCodes`, `isFinal403`, `isRetryable`, `apiCall` |
| `core/network/di` | `NetworkModule` (ApiConfig, OkHttpClient, Retrofit, `OperationalApi`, `MeApi`), `IdTokenModule` |
| Tests | 7 new classes, 63 tests; `MainActivityTest` now expects exactly the `INTERNET` permission |
| Docs | ADR 0009, diagram `008-android-network-layer`, `android/README.md` ("Talking to the backend", structure, versions), `android/THIRD_PARTY.md`, `docs/adr/README.md`, one rule block in `CLAUDE.md` "Android rules", this log |

**Size:** about 2,300 hand-written lines without this log: Kotlin 669 (with KDoc), tests 1,268,
build files, manifest and XML 140, documents 206. Far over the ~800-line guide even after the
split; the commits are separate review units (spike, build, R2, R3, R4, R5/R6, tests, docs).
Generated code: 12 files, none tracked.

**API contract diff:** none. **Migrations:** none. **Deployment impact:** none; nothing under
`backend/`, no deploy workflow, no cloud configuration and no secret changed.

**Strings:** none added (no UI in this part), so there is nothing new for Bengali review.

## 5. Diagram

[`docs/diagrams/008-android-network-layer.svg`](../diagrams/008-android-network-layer.svg): a
screen → `apiCall` → generated interfaces (written from `contracts/openapi.json` at build time)
→ Retrofit → the OkHttp chain (RequestId, UserAgent, Auth, Retry) → the SafeRoute API;
`IdTokenProvider` feeding the Auth interceptor and the authenticator, with the Firebase token
source of P009 marked as not built; the error mapper → `ApiResult`. 16 nodes.

The generator has no dashed node style, so the P009 node is grey ("external") and says "not
built yet" instead of being dashed.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL |
| → lint | 0 errors, 2 warnings (both `OldTargetApi`, deliberate, ADR 0008) |
| → unit tests | **121 tests, 0 failures, 0 skipped** (58 before; 63 new) |
| → assembleDebug | `app-debug.apk` built |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | BUILD SUCCESSFUL (`app-release-unsigned.apk`) |
| `pnpm generate` in `tools/diagrams` | no errors; PNG checked by eye |
| markdownlint-cli2 0.18.1 | 0 errors |
| JSON validity | all valid |
| gitleaks 8.30.1 | no leaks |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services"` | no output |

`android-ci` and `repo-checks` run on the pull request; their result is added below once known.

| Class | Tests | Covers |
| --- | --- | --- |
| `GeneratedClientTest` | 11 | the four operations decode the backend's real JSON shapes; 200 vs 201 of `bootstrapMe`; request path, method, content type and body; nullable fields; unknown extra fields ignored; unknown `role` / `locale` pass through; unknown value of a closed enum, HTML and incomplete JSON on a 200 are handled failures; a null `@Body` is refused before anything is sent |
| `RequestHeadersAndAuthTest` | 11 | `X-Request-Id` well-formed and different per request, kept when the caller set one; `User-Agent`; no `Authorization` when signed out; token sent to the API; **never sent to a second server that differs only by port**, and a 401 from that server triggers no refresh; 401 → one refresh, one retry (GET and POST); second 401 → gives up after 2 requests; 401 without a token → no refresh; refresh returning null or the same token → gives up; a token source that throws → `NoConnection`, no crash |
| `RetryInterceptorTest` | 10 | GET retried on 503 after 300 and 900 ms (recorded, no real waiting); gives up after two extra attempts; jitter added; `Retry-After` in seconds, capped at 5 s, as a date, nonsense ignored; GET retried on `IOException`; HEAD like GET; POST, PUT, PATCH, DELETE never retried (503 and `IOException`); 400, 401, 403, 404, 409, 429, 500, 502 never retried |
| `ApiErrorMappingTest` | 15 | each of the 15 known codes; unknown code kept; field errors; `application/json` accepted; 401 → `Unauthorized`; missing fields, wrong shape, plain text, HTML, no content type, empty body → `Unexpected(status)` without the body; body over 64 KiB not parsed; request-id fallback to the header; `isRetryable` / `isFinal403`; `IOException` → `NoConnection`; other exceptions → `Unexpected`; **`CancellationException` propagates**; through the real client: 503 after retries is a retryable `Problem`, 403 `bootstrap_required` is final and sent once, refused connection and timeout → `NoConnection` |
| `SafeLoggingTest` | 4 | with the debug logger on, a request carrying a token, a query string with a phone number, cookies and bodies logs one line with none of them, nor host and port; a failed attempt logs only the exception's class name; every retry attempt is logged; logger off → nothing logged and the interceptor is not installed |
| `ApiConfigTest` | 8 | base URL rule (https, trailing slash, no unsafe characters); the message never repeats the value; `toString` hides the URL; same scheme, host and port only; the network security config refuses cleartext for every host, has no exceptions and trusts system authorities only; the manifest references it |
| `NetworkModuleTest` (Hilt) | 4 | the graph provides the config, one client with the interceptors in order, the authenticator and the four timeouts, the signed-out token provider, and both generated interfaces |

**What failed on the way:** `HEAD is retried like GET` timed out. The test queued a 503 with a
body for a HEAD request, which is not valid HTTP and confused the connection. The test now
queues body-less responses. No production code changed because of it.

**Checks done by hand (Gradle, with dummy values only):**

| Command | Result |
| --- | --- |
| `-Psaferoute.apiBaseUrl=http://example.invalid/` | configuration fails: "saferoute.apiBaseUrl is malformed: it must start with https:// and end with / …" (value not printed) |
| `-Psaferoute.apiBaseUrl=https://example.invalid` | same failure |
| `:app:preReleaseBuild -Psaferoute.apiBaseUrl=` (empty = not set) | `checkReleaseApiBaseUrl` FAILED: "Release builds need the Gradle property saferoute.apiBaseUrl …" |
| `:app:preReleaseBuild -Psaferoute.apiBaseUrl=https://example.invalid/` | BUILD SUCCESSFUL |
| `:app:preDebugBuild -Psaferoute.apiBaseUrl=` | BUILD SUCCESSFUL (placeholder) |

**Not verified:** anything on a device or against the real staging server. No request was sent
to staging: nothing in the app calls the API until P008b's debug screen. That Android itself
blocks `http://` on a phone is asserted from the configuration file, not observed.

**SOS failure matrix (Plan v7 §7.5):** not applicable. No SOS, live-location or contacts logic.
The retry and idempotency rules written here are what the matrix rows "API down / 5xx" and
"FCM failure" will rely on in P013 to P015.

## 7. Decisions & ADRs

[ADR 0009](../adr/0009-android-api-client.md) (Accepted) records the lasting decisions:
generated at build time and never committed; generator and settings; only `core/network` knows
OkHttp and Retrofit; the token seam; tokens only to the API; one refresh on 401; retries for
idempotent requests only; no body logging; strict JSON; HTTPS only; base URL from a user-level
Gradle property; release fails without it; no pinning for now.

Smaller choices and deviations from the prompt's wording:

- **Split into P008a / P008b** (section 1). Unlike the prompt's suggested boundary, ADR 0009, the
  README section, the `CLAUDE.md` rules and the diagram are in **part a**: they describe this
  code, and the code's comments cite the ADR. Part b keeps the debug screen and CI.
- **"Same host" is same scheme, host and port.** Stricter than the prompt's "host equals".
- **`apiCall` takes a block returning `Response<T>`,** because the generated methods return
  that. `Success` also carries the HTTP status and the request id.
- **`isFinal403` is a property of the failure** (`failure.isFinal403`), true for every 403
  whatever its code, instead of `isFinal403(code)`: a function of the code would have to return
  true for every input.
- **A problem body with missing required fields becomes `Unexpected(status)`.** It is decoded
  with the generated `ProblemDetails`; a second, lenient hand-written schema would contradict
  "never hand-write models".
- **`ApiFailure.Unexpected.status` is nullable** (a success body that fails to decode has no
  error status), and `Unauthorized` / `Unexpected` carry the request id for bug reports.
- **The base URL rule exists twice:** in `app/build.gradle.kts` (configuration time) and in
  `ApiBaseUrl` logic inside `ApiConfig.kt` (runtime, unit-tested). A shared build-logic module
  for two lines was not worth a second Gradle project; the Gradle side is covered by the manual
  checks above.
- **The release check is a task (`checkReleaseApiBaseUrl`), not a configuration-time error,** so
  that Android Studio sync and debug builds work without the property.
- **`runBlocking` is used in the interceptor as well as the authenticator.** Both need the
  `suspend` token function and both run on an OkHttp worker thread. It is in one helper
  (`idTokenBlocking`), which also turns any failure into an `IOException` so that a broken token
  source cannot crash the app.
- **The debug logger is in `main` and installed only when `BuildConfig.DEBUG`.** Release builds
  contain the class but never add it to the client (tested).
- **`usesCleartextTraffic` is not set:** from API 24 the network security config decides, and
  minSdk is 26.
- **`HTTP-date` in `Retry-After` is supported** with the injected `Clock`, the first production
  user of `CoreModule`'s clock.

### R0 spike: does OpenAPI Generator handle the 3.1 contract?

**Result: yes.** The generated client compiles without warnings against Kotlin 2.4.20 and
AGP 9.4.1 and nothing had to be changed in `contracts/`.

| Item | Value |
| --- | --- |
| Tool | OpenAPI Generator **7.25.0**, Gradle plugin `org.openapi.generator` 7.25.0 (Gradle Plugin Portal; the marker resolves to `org.openapitools:openapi-generator-gradle-plugin:7.25.0` on Maven Central). Apache-2.0. Build time only: nothing of it is packaged into the app. |
| Input | `contracts/openapi.json` (OpenAPI 3.1.0, contract version 0.2.0), unchanged |
| Generator | `kotlin`, `library = jvm-retrofit2` |
| `configOptions` | `serializationLibrary = kotlinx_serialization`, `useCoroutines = true`, `useResponseAsReturnType = true`, `dateLibrary = java8`, `omitGradleWrapper = true` |
| `globalProperties` | `apis = ""`, `models = ""` (all of them), `supportingFiles = CollectionFormats.kt,OffsetDateTimeAdapter.kt,UUIDAdapter.kt`, `apiDocs`, `modelDocs`, `apiTests`, `modelTests` = `false` |
| Output | `android/app/build/generated/openapi` (ignored by git through `/build`); 12 Kotlin files |
| Option names | checked against `docs/generators/kotlin.md` and the plugin README at the `v7.25.0` tag |

**Warnings.** One notice from the generator: *"OpenAPI 3.1 support is still in beta."* No
warning about this spec, and spec validation (on by default) passed. No Kotlin compiler
warnings in the generated code. The plugin works with Gradle's configuration cache; a second
build reports `generateApiClient UP-TO-DATE`.

| Question | Finding |
| --- | --- |
| API interfaces | One Retrofit interface per tag: `OperationalApi` (`getHealth`, `getReadiness`) and `MeApi` (`bootstrapMe`, `getMe`). Method names are the spec's operationIds. All are `suspend` and return `retrofit2.Response<T>`. Paths are relative (`health`, `v1/me`), so the base URL must end with `/`. |
| Models | `@Serializable` data classes with `@SerialName` on every property: `Health`, `Readiness`, `ReadinessChecks` (the inline `checks` object), `Me`, `BootstrapMeRequest`, `ProblemDetails`, `ValidationIssue`. `IdempotencyKey` (a plain string schema) produces no class. |
| 3.1 nullable (`type: ["string","null"]`) | Correct: `Me.phoneE164` and `Me.displayName` are `String?` **without** a default, so the key is required and its value may be null. |
| Optional properties | `T? = null` (`ProblemDetails.errors`, both `BootstrapMeRequest` fields). |
| Open strings (`code`, `role`, `locale`) | Plain `String`. Unknown values pass through. |
| `enum` in the spec | A closed Kotlin `enum class` (`Health.Status { ok }`, `Readiness.Status { ready }`, `ReadinessChecks.Database { ok }`, `BootstrapMeRequest.Locale { en, bn }`). A value outside the enum fails decoding with a `SerializationException`; it does not crash the app because `apiCall` turns it into `ApiFailure.Unexpected` (tested). |
| `format: uuid`, `format: date-time` | `java.util.UUID` and `java.time.OffsetDateTime`, marked `@Contextual`. The generator's own `UUIDAdapter` and `OffsetDateTimeAdapter` are kept and registered in the app's `Json`. `java.time` is available from API 26, the app's minSdk. |
| problem+json errors | **Not typed in the interfaces.** A method returns `Response<SuccessType>` for every status; the 4xx/5xx bodies of the spec appear only in KDoc. `ProblemDetails` is generated as a model, and the app decodes `errorBody()` with it (R6). 200 and 201 of `bootstrapMe` share the type `Me`; the status code tells them apart. |
| Security scheme | Not represented in the interfaces (no `Authorization` parameter). The generator's `HttpBearerAuth` and `ApiClient` are not generated; the app's own interceptor adds the token (R4). |
| Optional request body | `bootstrapMe(@Body bootstrapMeRequest: BootstrapMeRequest? = null)`. **Retrofit rejects a null `@Body`** ("Body parameter value must not be null"), so the default must not be used: callers pass `BootstrapMeRequest()`, which is sent as `{}`. Covered by a test. |

**Things that did not work the first way:**

- `android.sourceSets["main"].kotlin.srcDir(provider)` is refused by AGP 9 ("You cannot add
  Provider instances to the Android SourceSet API"). The generated folder is registered through
  the variant API instead (`variant.sources.kotlin.addGeneratedSourceDirectory`), which also
  makes KSP, compile and lint depend on `generateApiClient`.
- `CollectionFormats.kt` is needed although the contract has no collection parameters: the
  generated interfaces import it.
- The generator's `ApiClient.kt` was left out on purpose: it needs two more libraries (OkHttp's
  logging interceptor and Retrofit's scalars converter), can log request bodies and configures a
  lenient `Json`.

**Contract observation (no change made; ADR 0004 owns the contract):** single-value enums on
**response** fields (`Health.status`, `Readiness.status`, `Readiness.checks.database`) are
closed on the client. If the backend ever adds a value such as `degraded`, older app versions
fail to decode that response (as a handled failure). Recorded as a follow-up.

## 8. Security & privacy notes

- **Tokens:** never stored by the network layer, never logged, attached only to requests with
  the API's scheme, host and port, and only over HTTPS in a real build. Tested, including a
  second server on the same host.
- **No cleartext:** refused for every host by the network security config; user-installed
  certificate authorities are not trusted, in debug builds too.
- **Logging:** no body, header, host or query string is ever logged; release builds log nothing.
  The debug line contains the path; today no path contains personal data. A later prompt that
  puts an identifier or a share token in a path must review that (follow-up).
- **Permission added: `android.permission.INTERNET`.** A "normal" permission: granted at
  install, no dialog. Needed to reach the API. Nothing else was added.
- **The base URL is not in the repository.** Check on the branch diff: no `run.app` or other
  cloud host; the only URLs added are `https://api.invalid/`, `*.invalid` and `host.example`
  test values and two documentation links. `git grep saferoute.apiBaseUrl` finds only the
  property's name, never a value. No local path, user name or e-mail address in the diff.
  Gradle output pasted nowhere.
- **Generated code and build output are not tracked** (`git ls-files | grep core/network/generated`
  → 0).
- **Supply chain:** OpenAPI Generator 7.25.0 comes from the Gradle Plugin Portal (plugin id
  `org.openapi.generator`, which resolves to the `org.openapitools` artifacts on Maven Central),
  version pinned in the catalog, Apache-2.0, build time only. OkHttp 5.5.0 (with Okio 3.18.1),
  Retrofit 3.0.0, its converter 3.0.0, kotlinx-serialization-json 1.11.0 and MockWebServer
  5.5.0 are Apache-2.0 (read from the published POMs). No copyleft-incompatible or proprietary
  licence. Listed in `android/THIRD_PARTY.md`.
- **New personal data:** none stored. `Me` (phone number, display name) can now be decoded but
  nothing requests or keeps it yet.

## 9. Known issues & risks

- **Nothing in the app calls the API yet,** so the client has never talked to the real backend.
  The first real request happens on Rahul's phone with P008b's debug screen.
- **OpenAPI 3.1 support in the generator is labelled beta.** `GeneratedClientTest` is the guard.
- **Closed enums on response fields** (section 7).
- **OkHttp's own connection recovery (`retryOnConnectionFailure`) is left on.** It is separate
  from `RetryInterceptor` and re-sends a request when a connection attempt failed, which in rare
  cases can include a POST on a stale pooled connection. To be settled with the Idempotency-Key
  policy (P013 to P015), before any write that must not happen twice exists.
- **The first build after a checkout downloads the generator** (and needs the Gradle Plugin
  Portal); the generator adds about half a minute when the contract changed.
- **`android-ci` does not yet run on `contracts/**` changes and does not build the release
  variant.** Both come with P008b (R8).
- `android/gradlew.bat` is modified locally and uncommitted (section 2).

## 10. Follow-ups & prerequisites for next prompt

Recorded in Notion (*Follow-ups*, source P008a): Firebase `IdTokenProvider` (P009) ·
Idempotency-Key retry policy for writes, including OkHttp's connection recovery (P013–P015) ·
certificate pinning decision · offline and caching strategy · generated-code and generator
upgrade policy · release base URL injection in CI (P022) · open strings instead of closed enums
on response fields (contract) · review the debug log line when a path can carry an identifier ·
decide about `gradlew.bat` (still open from P007b).

Left for **P008b** (`feat/008b-…`, after this pull request is merged): R7 debug-only
connectivity check screen with EN/BN strings and tests; R8 `android-ci` path filter
`contracts/**` and `assembleRelease` with a dummy URL; "consider making android-ci required"
and "Bengali review of new strings" follow-ups; the manual check against staging.

Still open from P007: Bengali review, fonts, app icon and splash, instrumented tests,
`android-ci` as a required check, R8 and baseline profiles, targetSdk 37.

## 11. How Rahul can verify

1. Read the pull request, commit by commit (spike, build, base URL, token seam, OkHttp stack,
   Retrofit and errors, tests, docs).
2. Make sure `saferoute.apiBaseUrl` is in your **user-level** `gradle.properties` (see
   `android/README.md` → "Talking to the backend"). Don't paste the value anywhere.
3. In Android Studio: *File → Sync Project with Gradle Files*. Then open
   `app/build/generated/openapi` in the Project view ("Project" mode, not "Android") and look at
   `MeApi.kt` and `Me.kt`: this is what the generator wrote from the contract.
4. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug` → green, 121 tests.
5. Optional: `./gradlew assembleRelease` → green with your property. Then
   `./gradlew :app:preReleaseBuild "-Psaferoute.apiBaseUrl="` → fails with "Release builds need
   the Gradle property…" (an empty value counts as not set).
6. Run the app on your phone. It must look and behave exactly as after P007b: this part adds no
   screen. (*Settings → Apps → SafeRoute → Permissions* shows no permission to grant; the
   network permission is listed under "All permissions".)
7. CI green; squash and merge; delete the branch. Then paste P008 again (or ask for "P008b") for
   the debug screen and the CI changes.

Also still to decide: `android/gradlew.bat` (keep and commit in a later prompt, or
`git restore android/gradlew.bat`).

## 12. Learning notes

- **HTTP client.** The part of an app that sends requests to a server and reads the answers.
  **OkHttp** does the actual work (connections, TLS encryption, timeouts). **Retrofit** sits on
  top: you describe the API as a Kotlin interface (`suspend fun getMe(): Response<Me>`) and
  Retrofit creates the object that sends the request and decodes the JSON.
  <https://square.github.io/okhttp/> · <https://square.github.io/retrofit/>
- **Code generation from the contract.** `contracts/openapi.json` is the single description of
  the API, produced by the backend. A tool reads it during the build and writes the Kotlin
  interfaces and data classes. Nobody types them, so the app and the server can't silently
  disagree: if the backend removes a field the app uses, the app no longer compiles.
- **kotlinx.serialization.** Turns JSON text into Kotlin objects and back. `@Serializable`
  classes get their converter generated by a compiler plugin.
- **Interceptor.** A step every request passes through before it is sent (and every response on
  the way back). Ours add the request id, the user agent and the token, retry, and write the
  debug log line. <https://square.github.io/okhttp/features/interceptors/>
- **Authenticator.** OkHttp calls it when the server answers 401 ("who are you?"). Ours asks
  for a fresh token once and repeats the request once.
- **Coroutine and `suspend` function.** A coroutine is a piece of work that can pause (while
  waiting for the network) without blocking a thread, so the screen stays responsive. A
  `suspend` function is one that may pause like that; it can only be called from a coroutine.
  <https://developer.android.com/kotlin/coroutines>
- **`CancellationException`.** When a screen closes, its coroutines are cancelled by throwing
  this exception through them. Catching and hiding it would keep dead work alive, which is why
  `apiCall` lets exactly this one through.
- **Idempotent retries.** An operation is idempotent if doing it twice has the same effect as
  doing it once. Reading (`GET`) is; "create an SOS session" (`POST`) is not. When a request
  fails we often can't know whether the server received it, so only idempotent requests are
  repeated automatically. Writes will carry an `Idempotency-Key` so that the server can
  recognise a repeat.
- **Backoff and jitter.** Waiting longer before each retry (300 ms, then 900 ms) gives a
  struggling server room; a small random extra ("jitter") stops thousands of phones from
  retrying in the same instant.
- **Why tokens go to the API only.** The ID token proves who you are. Any server that receives
  it could use it to act as you until it expires. So it is attached only to requests for our
  API, never to a map-tile or routing server.
- **`BuildConfig`.** A class generated at build time with facts about the build. Besides
  `DEBUG` and `VERSION_NAME` it now holds `API_BASE_URL`, filled from a Gradle property, so the
  address lives outside the source code.
- **Gradle properties, user-level.** `gradle.properties` in your home folder's `.gradle`
  directory applies to every project on your computer and is in no repository. That is where
  values go that are yours alone.
- **Cleartext traffic.** Plain `http://`, readable by anyone on the same Wi-Fi. The network
  security config tells Android to refuse it for the whole app.
  <https://developer.android.com/privacy-and-security/security-config>
- **Permissions, normal vs dangerous.** `INTERNET` is "normal": listed in the manifest, granted
  automatically. Location or SMS are "dangerous": the user must agree in a dialog. Those come
  with the prompts that need them.
- **MockWebServer.** A small real web server that runs inside a test and answers with whatever
  the test queued, so the real client code runs without the real backend.
- **Sealed types.** `ApiFailure` is `sealed`: the compiler knows every kind of failure, and a
  `when` that forgets one does not compile.
