# Observability — local dev

Limen uses the [Spring Boot 4 OpenTelemetry starter][sb-otel] to push metrics,
traces, and logs over OTLP/HTTP. In dev, all three signals land in a single
[Grafana LGTM][otel-lgtm] container — Grafana, Mimir (Prom-compatible TSDB),
Loki, Tempo, and an OTel Collector bundled together. Grafana Labs flag this
container as dev/demo only (no HA, no durable storage, default credentials);
production should point the OTel exporter at a real backend via env vars.

## Start the stack

The LGTM container sits behind the `observability` compose profile so it only
runs when explicitly requested:

```bash
docker compose --profile observability up -d
```

Then start Limen as usual:

```bash
./mvnw spring-boot:run
```

Spring Boot's Docker Compose support auto-detects the LGTM container and
wires `OTEL_EXPORTER_OTLP_ENDPOINT` (logs, metrics, traces) for the running
app — no env vars needed locally. Logs reach Loki via the
`opentelemetry-logback-appender-1.0` bridge declared in `logback-spring.xml`,
which `observability.OtelLogbackInstaller` hands the auto-configured OTel
SDK at startup; the Spring Boot OTel starter wires the SDK + OTLP log
exporter but does not ship a Logback bridge of its own, so without that
appender the log endpoint stays configured but unused.

Open Grafana at <http://localhost:3000> (login `admin` / `admin`).
The pre-provisioned datasources (`mimir`, `loki`, `tempo`) are ready to query.

## Import dashboards

The image ships with Grafana but no Spring Boot dashboards. Import these
two from grafana.com (one-time, persists in the container's volume across
restarts):

| ID | Name | Coverage |
|---|---|---|
| [19004][d-19004] | Spring Boot 3.x Statistics | HTTP requests, response latencies, JDBC pool, threads, CPU |
| [4701][d-4701] | JVM (Micrometer) | Heap, GC, classloading, thread states |

In Grafana: **Dashboards → New → Import → paste ID → Load → select `mimir`
data source → Import.**

If panels show "No data", the OTLP→Mimir metric-name translation may not
match what the dashboard expects — start with **Explore → mimir** and
search for `http_server_requests_seconds_count` or `jvm_memory_used_bytes`
to confirm the metrics are flowing.

## Custom counters

In addition to the stock auto-instrumentation (HTTP, JVM, JDBC, GC, threads),
`observability.AuditMetricsListener` exports three named auth counters:

| Metric | Trigger | Tags |
|---|---|---|
| `limen.auth.login.success` | Spring Security `AuthenticationSuccessEvent` | — |
| `limen.auth.login.failure` | any `AbstractAuthenticationFailureEvent` | `cause` (exception simple class name, e.g. `BadCredentialsException`) |
| `limen.oauth2.client.secret.rotated` | `ClientSecretRotatedEvent` (AFTER_COMMIT) | — |

No tenant tag — same cardinality rule as the section below. Token-issuance
volume and latency are already covered by stock `http.server.requests` for
`/oauth2/token` (URI templates, no tenant tag), so a custom counter would be
redundant.

## Tenant attribution

`TenantObservabilityFilter` sets the active tenant's `tenant.slug` and
`tenant.id` on the request span (queryable in Tempo) and on the SLF4J MDC
(emitted as structured fields on log records in Loki). They're **never**
applied as metric tags — that would inflate Prometheus/Mimir series count
linearly with tenant count.

In Grafana Explore:

- **Logs by tenant** — Loki: `{service_name="limen"} | tenant.slug = "acme"`
- **Traces by tenant** — Tempo: filter by attribute `tenant.slug = "acme"`

## Production

Drop the LGTM container; point the OTel SDK at a real backend via standard
env vars at deploy time:

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp-gateway.example.com
OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <token>"
OTEL_RESOURCE_ATTRIBUTES=deployment.environment=production,service.version=0.1.2
OTEL_TRACES_SAMPLER_ARG=0.1   # 10% trace sampling
```

Likely targets:

- [Grafana Cloud][gc-pricing] — free tier (10k metric series, 50 GB
  logs/traces, 14-day retention) covers Limen at any plausible scale.
- Self-hosted Mimir / Loki / Tempo (Helm charts on Kubernetes; or
  single-VM deployments for smaller scale).
- Any OTLP-compatible backend (Datadog, Honeycomb, New Relic, AWS).

[sb-otel]: https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/
[otel-lgtm]: https://github.com/grafana/docker-otel-lgtm
[d-19004]: https://grafana.com/grafana/dashboards/19004-spring-boot-statistics/
[d-4701]: https://grafana.com/grafana/dashboards/4701-jvm-micrometer/
[gc-pricing]: https://grafana.com/pricing/
