#ifndef __DONK_GAME_H__
#define __DONK_GAME_H__

#include <cstdint>
#include <span>
#include <vector>
#include "recomp.h"

namespace dk64 {
    std::vector<uint8_t> decompress_dk(std::span<const uint8_t> compressed_rom);
    void dk_on_init(uint8_t* rdram, recomp_context* ctx);
};

#endif
