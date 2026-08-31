# Real CVAT development deployment

This runs **upstream CVAT**, including its UI, database, image-processing workers
and annotation storage. There is no fake CVAT server in the BTC project.

## Running instance

On 2026-08-30, CVAT 2.74.0 was deployed on `tdx2` at
<http://100.112.65.68:8080>. The HTTP port binds only to the Tailscale IP, not
the public interface. This is a development instance, not a production service.
Other workloads on the host were not changed.

- Deployment directory: `/home/tdx2/human-protocol-btc-cvat`
- Compose project: `hpb-cvat`; all container names start with `hpb_cvat_`.
- Upstream commit: `c494299bbd225d6d0fc5e8a5e2668447abf50d70` (v2.74.0).
- Observed server image digest:
  `sha256:4ee44b2e8746f6fa47c35322442c852063e8571e375989219dad25ea669b378e`.
- Login: `hpb-owner`. Generated password/token are in the mode-0600 file
  `.local/cvat/credentials.json` under the deployment directory and in the local
  BTC checkout. Never commit this file, print it into CI logs, or upload it.

The existing BTC bridge is a classification/tag workflow. Deploying CVAT does
**not** implement the original HUMAN CVAT oracle stack. See
[the architecture comparison](../../docs/cvat-oracle-comparison.md).

## Reproduce the deployment

Requires Git, Docker with Compose >= 2.24.4 (`!override` support), curl and jq.
The published CVAT server image used here is linux/amd64; tdx2 runs it natively.
Use an adequately provisioned Linux host. The scripts fetch a pinned upstream
checkout and refuse modified upstream tracked files.

From the BTC repository root, for a loopback-only instance:

```sh
bash deploy/cvat/compose.sh up -d --quiet-pull
CVAT_URL=http://localhost:8080 bash deploy/cvat/wait-ready.sh
```

For a Tailscale-hosted instance, explicitly set both its router host and bind IP
on every Compose operation:

```sh
CVAT_HOST=100.112.65.68 CVAT_BIND_ADDRESS=100.112.65.68 \
  bash deploy/cvat/compose.sh up -d --quiet-pull
CVAT_URL=http://100.112.65.68:8080 bash deploy/cvat/wait-ready.sh
```

Create the development owner once, preserving credentials privately:

```sh
umask 077
set -o noclobber
docker exec -i hpb_cvat_server python manage.py shell --interface python --verbosity 0 \
  < deploy/cvat/provision.py > .local/cvat/credentials.json
```

Provisioning refuses to reset an existing account. Do not rerun it to recover
a lost password. This account is an admin for this development instance only;
production needs separately scoped credentials, TLS and operational hardening.

## Run the real-service test

Needs Java 21, Gradle 8.10.2 and `jq` — no bitcoind or relay, since this suite
only talks to CVAT. Then:

```sh
CVAT_URL=http://100.112.65.68:8080 \
HPB_CVAT_CREDENTIALS="$PWD/.local/cvat/credentials.json" \
  bash deploy/cvat/real-test.sh
```

`CVAT_TOKEN` may be supplied directly instead of the credentials file, and
`GRADLE_EXE` selects an installed Gradle. The script refuses to run without CVAT
configuration; the tests themselves skip when it is absent, so a plain
`gradle test` stays runnable with no deployment.

CI does **not** run this suite: the development instance is reachable only over
Tailscale, so there is no gate on it from GitHub Actions. It is a local check.

`:harness:realCvatTest` covers `CvatClient` against the real server and nothing
else — it creates a throwaway task, uploads three generated frames, waits for
CVAT's real asynchronous import, exercises every call the client makes, checks
the written tag reads back, and deletes the task again. It also asserts that a
bad token is refused with 401 and a missing task with 404. It does not start a
relay, a labeler or bitcoind; the escrow round trip remains
`:harness:test --tests "*CvatRoundTripTest"`, which runs against `MockCvat`.

Verified on 2026-08-30 against CVAT 2.74.0 at <http://100.112.65.68:8080>:
three tests, zero skips, zero failures. Reports land under
`kotlin/harness/build/reports/tests/realCvatTest/`.

An earlier session recorded a larger five-test real-service run on this
instance, including a regtest settlement and persisted tags on
[task 2](http://100.112.65.68:8080/tasks/2). That code was never committed and
is not in this tree; treat the claim as unreproduced.

This is **not** evidence of native-phone testing, public-signet settlement,
independent oracle operators, store distribution, or legacy-oracle parity.

## Stop without deleting data

```sh
bash deploy/cvat/compose.sh down
```

Compose volumes retain CVAT accounts, datasets and annotations. Do not use
`down -v` unless deliberately deleting this instance's data. Normal restarts
use `up -d`; they do not require reprovisioning the account.

## Real-service incompatibilities uncovered

Both were rediscovered independently against this deployment and are fixed in
this tree; see [the architecture comparison](../../docs/cvat-oracle-comparison.md).

- Java's HTTP/2 cleartext upgrade loses the request body through this CVAT proxy
  stack, so every call carrying one returns HTTP 400 from an empty body. Only
  `appendTags` carries a body, so the entire import path was broken against a
  real server while every read worked. `CvatClient` now pins HTTP/1.1. `MockCvat`
  cannot reproduce this — `com.sun.net.httpserver` is HTTP/1.1-only, so no
  upgrade is ever attempted.
- The bridge assumed every frame was PNG. CVAT serves `quality=compressed`
  frames as JPEG. `CvatClient.frame` now returns the served media type and the
  bridge builds its data URI from it.

Still open, and not addressed here: settlement precedes annotation import, so a
CVAT failure after payout can leave a paid job awaiting import. There is no
cross-system atomicity and no automatic recovery.

Upstream reference: [CVAT installation guide](https://docs.cvat.ai/docs/administration/basics/installation/).
