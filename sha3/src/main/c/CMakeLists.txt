cmake_minimum_required(VERSION 3.14)

set(HAVE_FLAG_SEARCH_PATHS_FIRST 0)
project(sha3_rocc)

add_subdirectory(spike_sim)

unset(ENV{CMAKE_OSX_SYSROOT})
unset(CMAKE_OSX_SYSROOT)
unset(ENV{CMAKE_OSX_DEPLOYMENT_TARGET})
unset(CMAKE_OSX_DEPLOYMENT_TARGET)

set(CMAKE_SYSTEM_NAME unknown-elf)
set(CMAKE_SYSTEM_PROCESSOR riscv64)
set(triple riscv64-unknown-elf)
set(TOOLCHAIN_PREFIX ${triple})

set(tools /opt/riscv)
set(CMAKE_C_COMPILER ${tools}/bin/${triple}-gcc)
set(CMAKE_CXX_COMPILER ${tools}/bin/${triple}-g++)
set(CMAKE_LINKER ${tools}/bin/${triple}-ld)
set(CMAKE_AR ${tools}/bin/${triple}-ar)

#set(CMAKE_C_COMPILER_TARGET ${triple})
#set(CMAKE_CXX_COMPILER_TARGET ${triple})

add_compile_options("$<$<CONFIG:DEBUG>:-DDEBUG>")

set(CMAKE_CXX_STANDARD 14)
SET(CMAKE_EXE_LINKER_FLAGS "-static")

include_directories(.)

if (SHA3_ROCC)
    add_compile_definitions(SHA3_ROCC)
endif (SHA3_ROCC)

add_executable(test.rv
        fips202.c
        fips202.h
        params.h
        perf.h
        poly.h
        polyvec.h
        sha3_inst.h
        symmetric-fips202.c
        symmetric.h
        test.c
        )

add_custom_target(run_spike
        COMMAND LD_LIBRARY_PATH=spike_sim spike --extension=sha3_rocc pk test.rv
        DEPENDS test.rv sha3_rocc
        WORKING_DIRECTORY ${CMAKE_PROJECT_DIR}
        )