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
constexpr int kV4l2CtrlTxPsRepeatCount     = kV4l2PrivateBase + 0x15; // 0x8000015
constexpr int kV4l2CtrlStopRdsTxPsName     = kV4l2PrivateBase + 0x16; // 0x8000016
constexpr int kV4l2CtrlStopRdsTxRt         = kV4l2PrivateBase + 0x17; // 0x8000017
constexpr int kV4l2CtrlIoverc              = kV4l2PrivateBase + 0x18; // 0x8000018
constexpr int kV4l2CtrlIntdet              = kV4l2PrivateBase + 0x19; // 0x8000019
constexpr int kV4l2CtrlMpxDcc              = kV4l2PrivateBase + 0x1a; // 0x800001a
constexpr int kV4l2CtrlAfJump              = kV4l2PrivateBase + 0x1b; // 0x800001b
constexpr int kV4l2CtrlRssiDelta           = kV4l2PrivateBase + 0x1c; // 0x800001c
constexpr int kV4l2CtrlHlsi                = kV4l2PrivateBase + 0x1d; // 0x800001d
constexpr int kV4l2CtrlSoftMute            = kV4l2PrivateBase + 0x1e; // 0x800001e
constexpr int kV4l2CtrlRivaAccessAddress   = kV4l2PrivateBase + 0x1f; // 0x800001f
constexpr int kV4l2CtrlRivaAccessLength    = kV4l2PrivateBase + 0x20; // 0x8000020
constexpr int kV4l2CtrlRivaPeek            = kV4l2PrivateBase + 0x21; // 0x8000021
constexpr int kV4l2CtrlRivaPoke            = kV4l2PrivateBase + 0x22; // 0x8000022
constexpr int kV4l2CtrlSsbiAccessAddress   = kV4l2PrivateBase + 0x23; // 0x8000023
constexpr int kV4l2CtrlSsbiPeek            = kV4l2PrivateBase + 0x24; // 0x8000024
constexpr int kV4l2CtrlSsbiPoke            = kV4l2PrivateBase + 0x25; // 0x8000025
constexpr int kV4l2CtrlTxTone              = kV4l2PrivateBase + 0x26; // 0x8000026
constexpr int kV4l2CtrlRdsGroupCounters    = kV4l2PrivateBase + 0x27; // 0x8000027
constexpr int kV4l2CtrlSetNotchFilter      = kV4l2PrivateBase + 0x28; // 0x8000028
constexpr int kV4l2CtrlAudioPath           = kV4l2PrivateBase + 0x29; // 0x8000029
constexpr int kV4l2CtrlDoCalibration       = kV4l2PrivateBase + 0x2a; // 0x800002a
constexpr int kV4l2CtrlSearchAlgorithm     = kV4l2PrivateBase + 0x2b; // 0x800002b
constexpr int kV4l2CtrlIrisSinr            = kV4l2PrivateBase + 0x2c; // 0x800002c
constexpr int kV4l2CtrlIntfLowThreshold    = kV4l2PrivateBase + 0x2d; // 0x800002d
constexpr int kV4l2CtrlIntfHighThreshold   = kV4l2PrivateBase + 0x2e; // 0x800002e
constexpr int kV4l2CtrlSinrThreshold       = kV4l2PrivateBase + 0x2f; // 0x800002f
constexpr int kV4l2CtrlSinrSamples         = kV4l2PrivateBase + 0x30; // 0x8000030
constexpr int kV4l2CtrlSpurFreq            = kV4l2PrivateBase + 0x31; // 0x8000031
constexpr int kV4l2CtrlSpurFreqRmssi       = kV4l2PrivateBase + 0x32; // 0x8000032
constexpr int kV4l2CtrlSpurSelection       = kV4l2PrivateBase + 0x33; // 0x8000033
constexpr int kV4l2CtrlUpdateSpurTable     = kV4l2PrivateBase + 0x34; // 0x8000034
constexpr int kV4l2CtrlValidChannel        = kV4l2PrivateBase + 0x35; // 0x8000035
constexpr int kV4l2CtrlAfRmssiThreshold    = kV4l2PrivateBase + 0x36; // 0x8000036
constexpr int kV4l2CtrlAfRmssiSamples      = kV4l2PrivateBase + 0x37; // 0x8000037
constexpr int kV4l2CtrlGoodChannelRmssi    = kV4l2PrivateBase + 0x38; // 0x8000038
constexpr int kV4l2CtrlSearchAlgorithmType = kV4l2PrivateBase + 0x39; // 0x8000039
constexpr int kV4l2CtrlCf0Th12             = kV4l2PrivateBase + 0x3a; // 0x800003a
constexpr int kV4l2CtrlSinrFirstStage      = kV4l2PrivateBase + 0x3b; // 0x800003b
constexpr int kV4l2CtrlRmssiFirstStage     = kV4l2PrivateBase + 0x3c; // 0x800003c
constexpr int kV4l2CtrlRxRepeatCount       = kV4l2PrivateBase + 0x3d; // 0x800003d
constexpr int kV4l2CtrlRdsGroupCountersExt = kV4l2PrivateBase + 0x42; // 0x8000042

constexpr int kHalDefaultRdsGroupProcMask = 0xEF;
constexpr int kHalDefaultRawRdsGroupMask = 40;
