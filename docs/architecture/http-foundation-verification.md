# HTTP foundation verification

## Completed boundaries

Tasks 7.1–7.3 are implemented and verified on the JVM with PostgreSQL 18.

- `FoundationHttpTest`: CDI `ResponseManager`, explicit API DTO/envelope, shared
  `successful` success code, service-owned `reference-invalid` translation, and
  sanitized unexpected `server-error`. No local global exception mapper exists.
- `BusinessContextHttpTest`: absent/duplicate/invalid mandatory headers,
  canonical UUID validation, actor length, exact case-preserved Process-Id in
  successes and errors, 12 concurrent asynchronous requests with isolated CDI
  context/MDC, and an ensuing request/health check without correlation leakage.
- The filter targets exactly `/v1` and `/v1/*`, accepting Quarkus' leading-slash
  path representation. Response and request-end cleanup cover normal/error paths.
- Fixtures live in `src/verification/java`, compiled into tests only by default.
  Production business workflows are not introduced by these probes. Explicit
  packaged/native verification variants remain part of task 11.1.

## Task 7.3: service-owned JSON input translation

The implementation sets strict Jackson input and disables the
built-in `BuiltinMismatchedInputExceptionMapper`, following the Party baseline.
Explicit business-text deserialization reuses the pure canonicalization policy;
Bean Validation operates on the final text using code-point lengths and stable
field codes. `StrictJsonHttpTest.canonicalizesBeforeFieldValidationIncludingUppercaseExpansion`
passes, including trim, accents, whitespace rejection and uppercase expansion.

The original failure was incorrectly diagnosed as requiring a shared-library
change. Comparison with Party Registry showed the missing API-boundary step:
`NaturalPersonJsonReaderInterceptor` and `IdentifierSchemeJsonReaderInterceptor`
translate narrowly scoped request binding errors into a service-owned
`ApiResponseException`; the library then builds the final envelope. Party's
`NaturalPersonResourceContractTest` explicitly expects unknown fields to produce
`400 bad-request`.

Company now uses the same responsibility split, with explicit DTO opt-in via
`@CompanyJsonBody`. The server-only `CompanyJsonReaderInterceptor` translates
malformed JSON, mismatched input (including unknown properties), and input stream
constraints into `ApiResponseException(CompanyResponseCode.BAD_REQUEST)`.
The code stays in the microservice. The interceptor neither creates responses
nor replaces the shared global mapper, and the published library is unchanged.

```text
POST /v1/_verification/echo
Content-Type: application/json
Tenant-Id / User-Id / Process-Id: valid
{"name":"OK","unknown":true}

Observed after correction: HTTP 400, status=400, code=bad-request
```

Both `StrictJsonHttpTest` tests pass, including malformed/unknown/non-string/array
input, missing body/name, canonical text length after uppercase expansion and
stable field-level validation details. `CompanyJsonReaderInterceptorTest` verifies
successful reads are unchanged, unrelated body types are not intercepted, and
server-definition/I/O failures retain their original meaning rather than becoming
client errors. No submitted Jackson message/cause is retained in the translated
input failure.

Reproduction:

```shell
DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew test --tests '*StrictJsonHttpTest' --tests '*CompanyJsonReaderInterceptorTest' --tests '*ArchitectureTest'
```

Result: **8 tests passed** with the existing
`api-response-quarkus-errors:1.0.0-SNAPSHOT`. There is no shared-library blocker.
Later HTTP/native tasks remain separately tracked and have not been accepted by
these JVM tests.

## Tasks 7.4–7.7: framework, published contract and artifact isolation

- `FrameworkHttpTest` verifies 404 (including `/v1`), 405, 415 and sanitized 500,
  actual HTTP/envelope status equality and absence of internal data. The shared
  fallback is configured for `/v1/*` without a local global mapper.
- `OpenApiHttpTest` compares the versioned YAML with its exact packaged resource
  and the published management endpoint. `OpenApiAssertions` is in shared contract
  sources for reuse by native-process tests; it parses both documents, compares
  common components, validates example structure/bounds/formats and rejects
  published verification/future business paths. Health/OpenAPI need no context.
- `PaginationLibraryContractTest` checks actual shared serialization, metadata
  names/null omission and the decimal int64 expected-version convention.
- `ResourceSignatureTest` checks every current fixture endpoint's generic
  `Uni<RestResponse<ApiResponse<ApiDto>>>` signature and rejects representative
  bare DTO, Domain and persistence payload violations.
- `./gradlew test --tests '*ResourceSignatureTest' verifyProductionIsolation`
  passed. The real packaged production application JAR contains
  `META-INF/openapi.yaml` and no `api/verification` classes. This is JVM packaging
  evidence only; native exclusion/execution remain tracked in section 11.
