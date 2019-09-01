cmake_minimum_required(VERSION 3.14)

set(CMAKE_CXX_STANDARD 14)

add_library(sha3_rocc SHARED
        sha3_model.cc
        sha3_model.h
        sha3_rocc.cc
        )

if(NOT DEFINED ISA_SIM_DIR)
IF (DEFINED $ENV{ISA_SIM_DIR})
    set(ISA_SIM_DIR $ENV{ISA_SIM_DIR})
ELSE ()
    message (FATAL_ERROR "set ISA_SIM_DIR to the path containing compiled spike libraries and headers")
ENDIF ()
endif()

include_directories(${CMAKE_CURRENT_SOURCE_DIR})
include_directories(${ISA_SIM_DIR})
include_directories(${ISA_SIM_DIR}/riscv)
include_directories(${ISA_SIM_DIR}/softfloat)

link_directories(${ISA_SIM_DIR})

find_library(RISCV_LIB riscv ${ISA_SIM_DIR})
find_library(RISCV_SOFTFLOAT_LIB softfloat ${ISA_SIM_DIR})

target_link_libraries(sha3_rocc ${RISCV_LIB} ${RISCV_SOFTFLOAT_LIB})
