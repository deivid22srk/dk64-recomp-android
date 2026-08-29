# FindFreetype — módulo mínimo para o build Android do DK64-Recomp.
# O freetype é compilado do fonte via FetchContent no CMake raiz do Android
# (target `freetype`). Este módulo expõe o alvo Freetype::Freetype esperado
# pelo CMake do RmlUi sem buscar libs do sistema (inexistentes no NDK).

if(NOT TARGET freetype)
    message(FATAL_ERROR "FindFreetype(Android): target 'freetype' (FetchContent) não encontrado — inclua freetype antes do RmlUi.")
endif()

if(NOT TARGET Freetype::Freetype)
    add_library(Freetype::Freetype INTERFACE IMPORTED GLOBAL)
    set_target_properties(Freetype::Freetype PROPERTIES
        INTERFACE_LINK_LIBRARIES freetype
        INTERFACE_INCLUDE_DIRECTORIES "${freetype_SOURCE_DIR}/include;${freetype_BINARY_DIR}/include")
endif()

set(FREETYPE_FOUND TRUE)
set(Freetype_FOUND TRUE)
