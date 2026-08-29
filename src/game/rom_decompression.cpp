#include <cassert>
#include <cstring>
#include <fstream>

#include "miniz.h"

#include "librecomp/game.hpp"
#include "donk_game.h"

#ifdef _MSC_VER
inline uint32_t byteswap(uint32_t val) {
    return _byteswap_ulong(val);
}
#else
constexpr uint32_t byteswap(uint32_t val) {
    return __builtin_bswap32(val);
}
#endif

size_t decompress_bkzip(mz_stream* stream, std::span<const uint8_t> compressed_rom, uint32_t start, uint32_t end, std::vector<uint8_t>& out, size_t out_offset) {
    // Subtract 2 bytes of magic number and 4 bytes of size.
    uint32_t compressed_data_start = start + 0x6;

    uint8_t size0 = compressed_rom[start + 0x2 + 0x0];
    uint8_t size1 = compressed_rom[start + 0x2 + 0x1];
    uint8_t size2 = compressed_rom[start + 0x2 + 0x2];
    uint8_t size3 = compressed_rom[start + 0x2 + 0x3];
    size_t decompressed_size = (size0 << 24) | (size1 << 16) | (size2 << 8) | (size3 << 0);

    if (out.size() < decompressed_size + out_offset) {
        out.resize(decompressed_size + out_offset);
    }

    stream->avail_in = end - compressed_data_start;
    stream->next_in = reinterpret_cast<const Bytef*>(compressed_rom.data() + compressed_data_start);

    stream->avail_out = decompressed_size;
    stream->next_out = reinterpret_cast<Bytef*>(out.data() + out_offset);

    mz_inflate(stream, Z_NO_FLUSH);

    mz_inflateReset(stream);

    return decompressed_size;
}

// Produces a decompressed BK rom. This is only needed because the game has compressed code.
// For other recomps using this repo as an example, you can omit the decompression routine and
// set the corresponding fields in the GameEntry if the game doesn't have compressed code,
// even if it does have compressed data.
std::vector<uint8_t> dk64::decompress_dk(std::span<const uint8_t> compressed_rom) {
    // Sanity check the rom size and header. These should already be correct from the runtime's check,
    // but it should prevent this file from accidentally being copied to another recomp.
    if (compressed_rom.size() != 0x2000000) {
        assert(false);
        return {};
    }

    //TODO: implement
    std::vector<uint8_t> ret{};

    // Copy everything from the original ROM up until the first overlay into the decompressed ROM.
    ret.reserve(0x2000000);
    ret.assign(compressed_rom.begin(), compressed_rom.begin() + 0x2000000);

    return ret;
}

void dk64::dk_on_init(uint8_t* rdram, recomp_context* ctx) {
    MEM_W(0, 0xFFFFFFFF802FE1C0) = 0xAD170014; //write to 0x802FE1C0
    MEM_W(0, 0xFFFFFFFF802FE1C4) = 0x3C09A600; //write to 0x802FE1C4

    //recomp::do_rom_read(rdram, (int32_t)0x80000004, 0x10000554, 0x334);
    recomp::do_rom_read(rdram, (int32_t)0x80000008, 0x10000558, 0x330);

    // Initialize variables normally set by IPL3
    constexpr int32_t osTvType = 0x80000300;
    //constexpr int32_t osRomType = 0x80000304;
    constexpr int32_t osRomBase = 0x80000308;
    constexpr int32_t osResetType = 0x8000030c;
    constexpr int32_t osCicId = 0x80000310;
    //constexpr int32_t osVersion = 0x80000314;
    constexpr int32_t osMemSize = 0x80000318;
    //constexpr int32_t osAppNMIBuffer = 0x8000031c;
    MEM_W(osTvType, 0) = 1; // NTSC
    MEM_W(osRomBase, 0) = 0xB0000000u; // standard rom base
    MEM_W(osResetType, 0) = 0; // cold reset
    MEM_W(osMemSize, 0) = 8 * 1024 * 1024; // 8MB
    MEM_W(osCicId, 0) = 6105; // set CIC to 6105
}
