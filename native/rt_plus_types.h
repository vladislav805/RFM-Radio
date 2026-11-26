#pragma once

// FM RT+ content types (ETSI TS 102 980, Annex A).
enum {
    kRdsRtpContentTypeUnused = 0,     // Empty tag slot.
    kRdsRtpLastItemType = 11,         // ITEM.GENRE ends the ITEM.* range.
    kRdsRtpContentTypeTableSize = 64  // The broadcast content type occupies six bits.
};

#ifdef __cplusplus
constexpr bool is_rt_plus_field(int type) {
    return type > kRdsRtpContentTypeUnused && type < kRdsRtpContentTypeTableSize && type != 54 && type != 55;
}
#endif
