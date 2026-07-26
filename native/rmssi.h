#pragma once

constexpr int decode_rmssi(int value) {
    const unsigned int raw = static_cast<unsigned int>(value) & 0xffU;

    return raw >= 0x80U
            ? static_cast<int>(raw) - 0x100
            : static_cast<int>(raw);
}
