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

Or from the repository root:

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
