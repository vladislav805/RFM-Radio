# Native Integration Tests (`fmbin`)

Device-side integration tests for `fmbin` written in native TypeScript (`*.mts`) for Node.js 24 (`node --test`).

## Prerequisites

1. Node.js 24+
2. Connected rooted Android device with USB debugging enabled
3. Root (`su`) granted to `adb shell` (e.g. via Magisk / KernelSU)
4. Built native assets (`make build-native`)

## Running

```sh
cd integration-tests
npm test
```

To run only the device-free host stub tests (`host/*.test.mts`):

```sh
npm --prefix integration-tests run test:host
```

Or run a single suite from the repository root:

```sh
node --test integration-tests/fmbin.test.mts
```

## Environment Variables

| Variable | Default | Description |
| --- | --- | --- |
| `RFM_ADB_SERIAL` | — | Target device serial (`adb -s`) when multiple devices are connected |
| `RFM_REMOTE_FMBIN` | `/data/local/tmp/fmbin` | Remote path on the device |
| `RFM_SKIP_PUSH` | `0` | Set to `1` to skip pushing `fmbin-*` before tests |
| `RFM_REGION` | `eu` | Region passed to `enable` (`eu`, `us`, `jp`, `jp_wide`) |
| `RFM_INITIAL_FREQ` | `87500` | Initial frequency in kHz |
| `RFM_SECONDARY_FREQ` | `100000` | Target frequency in kHz for `setfreq` |
| `RFM_SEEK_TIMEOUT_MS` | `30000` | Timeout waiting for `seek_done` before starting `searchhw` |
| `RFM_SEARCH_TIMEOUT_MS` | `90000` | Maximum wait for scan completion. A `search_failed` event fails the test immediately. |
| `RFM_RDS_REQUIRE` | `ps,rt` | Comma-separated required RDS fields (`ps`, `rt`, `rt_plus`, `af`, or `all`) |
| `RFM_RDS_DWELL_MS` | `6000` | Per-station listen window (ms) in `rds.test.mts` |
| `RFM_RDS_MAX_SEEKS` | `35` | Maximum `seekhw 1` steps in `rds.test.mts` |

## RDS Coverage and Broadcast Fixtures

Any populated RT+ content type satisfies the `rt_plus` requirement, including station and programme information.

Live over-the-air stations do not always broadcast optional `RT+` (ODA group 3A/tags) or `AF` lists, so `rds.test.mts` defaults to `RFM_RDS_REQUIRE=ps,rt` and logs any unobserved optional fields as diagnostics rather than failures.

To deterministically validate all four groups (`RFM_RDS_REQUIRE=ps,rt,rt_plus,af` or `RFM_RDS_REQUIRE=all`), use a controlled low-power FM test transmitter / RF signal generator (such as an SDR or dedicated RDS encoder broadcasting PS, RT, RT+ 3A/tags, and AF) tuned cleanly inside the selected region, and point `RFM_INITIAL_FREQ` at the fixture carrier if desired.
