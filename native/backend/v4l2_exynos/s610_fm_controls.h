#pragma once

#include <stdint.h>

// S610 V4L2 frequency multiplier (FAC_VALUE in Samsung's radio-s610.c).
// The driver divides v4l2_frequency.frequency by 16000 to obtain kHz.
constexpr uint32_t kS610FreqMultiplier = 16000;

// S610 V4L2 standard control IDs and driver-specific values.
constexpr uint32_t kS610CidAudioVolume = 0x00980905;
constexpr uint32_t kS610CidAudioMute = 0x00980909;
constexpr uint32_t kS610CidTuneDeemphasis = 0x00980932;

constexpr int32_t kS610MuteOn = 0;
constexpr int32_t kS610MuteOff = 1;

constexpr int32_t kS610DefaultVolume = 6;

// S610 custom V4L2 control IDs (V4L2_CID_USER_S610_BASE = 0x00980900 + 0x1070).
constexpr uint32_t kS610CidUserBase = 0x00981970;
constexpr uint32_t kS610CidChSpacing = kS610CidUserBase + 0x01;
constexpr uint32_t kS610CidChBand = kS610CidUserBase + 0x02;
constexpr uint32_t kS610CidSoftStereoBlend = kS610CidUserBase + 0x03;
constexpr uint32_t kS610CidSoftStereoBlendCoeff = kS610CidUserBase + 0x04;
constexpr uint32_t kS610CidSoftMuteCoeff = kS610CidUserBase + 0x05;
constexpr uint32_t kS610CidRssiCurr = kS610CidUserBase + 0x06;
constexpr uint32_t kS610CidSnrCurr = kS610CidUserBase + 0x07;
constexpr uint32_t kS610CidSeekCancel = kS610CidUserBase + 0x08;
constexpr uint32_t kS610CidSeekMode = kS610CidUserBase + 0x09;
constexpr uint32_t kS610CidRdsOn = kS610CidUserBase + 0x0A;
constexpr uint32_t kS610CidIfCount1 = kS610CidUserBase + 0x0B;
constexpr uint32_t kS610CidIfCount2 = kS610CidUserBase + 0x0C;
constexpr uint32_t kS610CidRssiTh = kS610CidUserBase + 0x0D;
constexpr uint32_t kS610CidKernelVer = kS610CidUserBase + 0x0E;
constexpr uint32_t kS610CidSoftStereoBlendRef = kS610CidUserBase + 0x0F;

// S610 band identifiers matching region_configs[] in radio-s610.c.
constexpr int32_t kS610BandEuUs = 0;
constexpr int32_t kS610BandJapan = 1;
constexpr int32_t kS610BandJapanWide = 2;

// S610 spacing indices matching fm_freq_steps[] in the S610 driver.
constexpr int32_t kS610Spacing50Khz = 0;
constexpr int32_t kS610Spacing100Khz = 1;
constexpr int32_t kS610Spacing200Khz = 2;

// S610 RDS modes: raw 3-byte block stream disables the in-kernel parser.
constexpr int32_t kS610RdsDisable = 0;
constexpr int32_t kS610RdsEnableRaw = 1;

// Tuning/seek defaults observed in Samsung's FmRadioController_slsi.
constexpr int32_t kS610DefaultRssiThreshold = 156;
constexpr int32_t kS610DefaultIfCount1 = 4800;
constexpr int32_t kS610DefaultIfCount2 = 5600;
constexpr int32_t kS610DefaultBlendCoeff = 3172;
constexpr int32_t kS610DefaultSoftMuteCoeff = 16;

// S610 raw RDS 3-byte tuple layout (fm_rds_write_data in fm_rds.c).
constexpr int kS610RdsBlockSize = 3;
constexpr uint8_t kS610RdsBlkTypeMask = 0x07;
constexpr uint8_t kS610RdsErrorsShift = 3;
constexpr uint8_t kS610RdsErrorsMask = 0x03;
constexpr uint8_t kS610RdsErrorsUncorrectable = 3;

constexpr uint8_t kS610RdsBlkA = 0;
constexpr uint8_t kS610RdsBlkB = 1;
constexpr uint8_t kS610RdsBlkC = 2;
constexpr uint8_t kS610RdsBlkD = 3;
constexpr uint8_t kS610RdsBlkCPrime = 4;
