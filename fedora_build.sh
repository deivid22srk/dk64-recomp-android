#!/bin/bash

BUILD_TYPE="Release"
if [ "$1" == "Debug" ]; then
  BUILD_TYPE="Debug"
fi

echo "Building in ${BUILD_TYPE} mode..."

./N64Recomp us.toml
./RSPRecomp n_aspMain.toml

rm -rf build-cmake/

cmake -S . -B build-cmake \
  -DCMAKE_CXX_COMPILER=g++ \
  -DCMAKE_C_COMPILER=gcc \
  -DCMAKE_CXX_FLAGS="-include cstdint" \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,--allow-multiple-definition" \
  -G Ninja \
  -DCMAKE_BUILD_TYPE="${BUILD_TYPE}"

cmake --build build-cmake --target DK64Recompiled -j$(nproc) --config "${BUILD_TYPE}"

cp build-cmake/DK64Recompiled DK64Recompiled && \
chmod +x ./DK64Recompiled && \
./DK64Recompiled