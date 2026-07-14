#pragma once

#include <linux/videodev2.h>

#ifndef V4L2_CID_PRIVATE_BASE
#    define V4L2_CID_PRIVATE_BASE 0x8000000
#endif

// Qualcomm Tavarua private V4L2 controls shared by the legacy V4L2 backend
// and the HAL vendor interface. HAL-only IRIS controls use a different id range
// and stay near the HAL interface definitions.
constexpr int kV4l2PrivateBase = V4L2_CID_PRIVATE_BASE;

constexpr int kV4l2CtrlSearchMode          = kV4l2PrivateBase + 0x01; // 0x8000001
constexpr int kV4l2CtrlScanDwell           = kV4l2PrivateBase + 0x02; // 0x8000002
constexpr int kV4l2CtrlSearchOn            = kV4l2PrivateBase + 0x03; // 0x8000003
constexpr int kV4l2CtrlState               = kV4l2PrivateBase + 0x04; // 0x8000004
constexpr int kV4l2CtrlTransmitMode        = kV4l2PrivateBase + 0x05; // 0x8000005
constexpr int kV4l2CtrlRdsGroupMask        = kV4l2PrivateBase + 0x06; // 0x8000006
constexpr int kV4l2CtrlRegion              = kV4l2PrivateBase + 0x07; // 0x8000007
constexpr int kV4l2CtrlSignalThreshold     = kV4l2PrivateBase + 0x08; // 0x8000008
constexpr int kV4l2CtrlSearchPty           = kV4l2PrivateBase + 0x09; // 0x8000009
constexpr int kV4l2CtrlSearchPi            = kV4l2PrivateBase + 0x0a; // 0x800000a
constexpr int kV4l2CtrlSearchCount         = kV4l2PrivateBase + 0x0b; // 0x800000b
constexpr int kV4l2CtrlEmphasis            = kV4l2PrivateBase + 0x0c; // 0x800000c
constexpr int kV4l2CtrlRdsStandard         = kV4l2PrivateBase + 0x0d; // 0x800000d
constexpr int kV4l2CtrlChannelSpacing      = kV4l2PrivateBase + 0x0e; // 0x800000e
constexpr int kV4l2CtrlRdsOn               = kV4l2PrivateBase + 0x0f; // 0x800000f
constexpr int kV4l2CtrlRdsGroupProc        = kV4l2PrivateBase + 0x10; // 0x8000010
constexpr int kV4l2CtrlLowPowerMode        = kV4l2PrivateBase + 0x11; // 0x8000011
constexpr int kV4l2CtrlAntenna             = kV4l2PrivateBase + 0x12; // 0x8000012
constexpr int kV4l2CtrlRdsDataBuffer       = kV4l2PrivateBase + 0x13; // 0x8000013
constexpr int kV4l2CtrlPsAll               = kV4l2PrivateBase + 0x14; // 0x8000014
constexpr int kV4l2CtrlAfJump              = kV4l2PrivateBase + 0x1b; // 0x800001b
constexpr int kV4l2CtrlSoftMute            = kV4l2PrivateBase + 0x1e; // 0x800001e
constexpr int kV4l2CtrlRdsGroupCounters    = kV4l2PrivateBase + 0x27; // 0x8000027
constexpr int kV4l2CtrlAudioPath           = kV4l2PrivateBase + 0x29; // 0x8000029
constexpr int kV4l2CtrlRdsGroupCountersExt = kV4l2PrivateBase + 0x42; // 0x8000042
