# P008a: Android API client (generated from OpenAPI) and network stack

| Field | Value |
| --- | --- |
| Prompt | P008 · Android API client, **part a** of two |
| Milestone | M2 (depends on P004, P005, P007; all merged) |
| Branch | `feat/008a-android-api-client` |
| Date | 2026-10-03 |
| Plan refs | Plan v7 §5.1, §6.1–6.3, §12.2, §12.4, §17, §20; ADR 0004, ADR 0006, ADR 0008 |

The remaining sections are written by `/ship-prompt`. The spike result is recorded first, as
the prompt asks.

## R0 spike: does OpenAPI Generator handle the 3.1 contract?

**Result: yes.** The generated client compiles without warnings against Kotlin 2.4.20 and
AGP 9.4.1 and nothing had to be changed in `contracts/`. The work continues with R1.

### What was run

| Item | Value |
| --- | --- |
| Tool | OpenAPI Generator **7.25.0**, Gradle plugin `org.openapi.generator` 7.25.0 (Gradle Plugin Portal; the marker resolves to `org.openapitools:openapi-generator-gradle-plugin:7.25.0` on Maven Central). Apache-2.0. Build time only: nothing of it is packaged into the app. |
| Input | `contracts/openapi.json` (OpenAPI 3.1.0, contract version 0.2.0), unchanged |
| Generator | `kotlin`, `library = jvm-retrofit2` |
| `configOptions` | `serializationLibrary = kotlinx_serialization`, `useCoroutines = true`, `useResponseAsReturnType = true`, `dateLibrary = java8`, `omitGradleWrapper = true` |
| `globalProperties` | `apis = ""`, `models = ""` (all of them), `supportingFiles = CollectionFormats.kt,OffsetDateTimeAdapter.kt,UUIDAdapter.kt`, `apiDocs`, `modelDocs`, `apiTests`, `modelTests` = `false` |
| Output | `android/app/build/generated/openapi` (ignored by git through `/build`); 12 Kotlin files |
| Option names | checked against `docs/generators/kotlin.md` and the plugin README at the `v7.25.0` tag |

### Warnings

- One notice from the generator: *"OpenAPI 3.1 support is still in beta."* No warning about
  this spec itself, and spec validation (on by default) passed.
- No Kotlin compiler warnings in the generated code.
- The plugin works with Gradle's configuration cache; a second build reports
  `generateApiClient UP-TO-DATE`.

### Shape of the output

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

### Things that did not work the first way

- **`android.sourceSets["main"].kotlin.srcDir(provider)` is refused by AGP 9** ("You cannot add
  Provider instances to the Android SourceSet API"). The generated folder is registered through
  the variant API instead (`variant.sources.kotlin.addGeneratedSourceDirectory`), which also
  makes KSP, compile and lint depend on `generateApiClient`.
- **`CollectionFormats.kt` is needed** although the contract has no collection parameters: the
  generated interfaces import it. Without it the build fails with an unresolved reference.
- The generator's `ApiClient.kt` was left out on purpose: it needs two more libraries (OkHttp's
  logging interceptor and Retrofit's scalars converter), logs request bodies when asked to and
  configures a lenient `Json`. The prompt forbids body logging and asks for a strict `Json`.

### Contract observations (no change made; ADR 0004 owns the contract)

- Single-value enums on **response** fields (`Health.status`, `Readiness.status`,
  `Readiness.checks.database`) are closed on the client. If the backend ever adds a value such
  as `degraded`, older app versions fail to decode that response (as a handled failure). Recorded
  as a follow-up for a contract decision; nothing in `contracts/` was touched here.
