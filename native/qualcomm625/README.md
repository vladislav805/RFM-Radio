# Qualcomm driver
## Agreements
### Files
#### `main.c`

Entry point. Describes API names of methods, available via UDP datagram.

#### `fmcommon.h`

Common types and macros.

#### `fm_command.c` / `fm_command.h`

Describes complex commands to V4L2. All functions must have prefixed `fm_command_`.

#### `fm_ctl.c` / `fm_ctl.h`

Describes single commands to V4L2 and has radio file descriptor. All functions must have prefixed `fm_receiver_`.
