cmake_minimum_required(VERSION 3.22.1)

project(wlantool_native C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT ANDROID)
    message(FATAL_ERROR "This native build is intended for the Android NDK toolchain.")
endif()

if(NOT DEFINED APP_GENERATED_JNI_DIR OR APP_GENERATED_JNI_DIR STREQUAL "")
    message(FATAL_ERROR "APP_GENERATED_JNI_DIR must be provided by Gradle.")
endif()

get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../" ABSOLUTE)
set(ROOTFS_BUILDER_DIR "${PROJECT_ROOT}/rftoolbuilder")
set(DIST_DIR "${ROOTFS_BUILDER_DIR}/distfiles")
set(ZLIB_ARCHIVE "${DIST_DIR}/zlib-1.3.1.tar.gz")
set(PROOT_REV "58aad2cb1c36ea6af7b32d76ccd5bf8d0a967939")
set(PROOT_ARCHIVE "${DIST_DIR}/proot-android-compat-${PROOT_REV}.tar.gz")
set(MUSL_ARCHIVE "${DIST_DIR}/musl-1.2.6.tar.gz")
set(TALLOC_ARCHIVE "${DIST_DIR}/talloc-2.4.3.tar.gz")
set(ZLIB_PREP_DIR "${CMAKE_BINARY_DIR}/prepared-sources")
set(ZLIB_SRC_DIR "${ZLIB_PREP_DIR}/zlib-1.3.1")
set(OUTPUT_ABI_DIR "${APP_GENERATED_JNI_DIR}/${ANDROID_ABI}")
set(PROOT_WORK_DIR "${CMAKE_BINARY_DIR}/native-work/${ANDROID_ABI}")

foreach(required_path
        "${MUSL_ARCHIVE}"
        "${TALLOC_ARCHIVE}"
        "${ZLIB_ARCHIVE}"
        "${PROOT_ARCHIVE}")
    if(NOT EXISTS "${required_path}")
        message(FATAL_ERROR "Missing ${required_path}. Run rftoolbuilder/downloads.sh first.")
    endif()
endforeach()

file(MAKE_DIRECTORY "${OUTPUT_ABI_DIR}")
file(MAKE_DIRECTORY "${ZLIB_PREP_DIR}")

if(NOT EXISTS "${ZLIB_SRC_DIR}/zlib.h")
    file(REMOVE_RECURSE "${ZLIB_SRC_DIR}")
    file(ARCHIVE_EXTRACT INPUT "${ZLIB_ARCHIVE}" DESTINATION "${ZLIB_PREP_DIR}")
endif()

set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${OUTPUT_ABI_DIR}")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${OUTPUT_ABI_DIR}")

get_filename_component(TOOLCHAIN_BIN_DIR "${CMAKE_C_COMPILER}" DIRECTORY)
find_program(GNU_MAKE NAMES make gmake REQUIRED)
find_program(LLVM_AR NAMES llvm-ar REQUIRED PATHS "${TOOLCHAIN_BIN_DIR}" NO_DEFAULT_PATH)
find_program(LLVM_RANLIB NAMES llvm-ranlib REQUIRED PATHS "${TOOLCHAIN_BIN_DIR}" NO_DEFAULT_PATH)
find_program(LLVM_STRIP NAMES llvm-strip REQUIRED PATHS "${TOOLCHAIN_BIN_DIR}" NO_DEFAULT_PATH)
find_program(LLVM_OBJCOPY NAMES llvm-objcopy REQUIRED PATHS "${TOOLCHAIN_BIN_DIR}" NO_DEFAULT_PATH)
find_program(LLVM_OBJDUMP NAMES llvm-objdump REQUIRED PATHS "${TOOLCHAIN_BIN_DIR}" NO_DEFAULT_PATH)

set(PROOT_OUTPUT "${OUTPUT_ABI_DIR}/libproot.so")
set(PROOT_LOADER_OUTPUT "${OUTPUT_ABI_DIR}/libproot-loader.so")

set(PROOT_PATCHES
        "${CMAKE_CURRENT_SOURCE_DIR}/patches/proot/src/extension/ashmem_memfd/ashmem_memfd.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/patches/proot/src/extension/sysvipc/sysvipc_msg.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/patches/proot/src/extension/sysvipc/sysvipc_shm.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/patches/proot/src/syscall/rlimit.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/patches/proot/src/tracee/seccomp.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/shims/linux/net.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/shims/sys/queue.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/talloc-config.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/build-proot.sh")

add_custom_command(
        OUTPUT "${PROOT_OUTPUT}" "${PROOT_LOADER_OUTPUT}"
        COMMAND "${CMAKE_COMMAND}" -E env
            "ABI=${ANDROID_ABI}"
            "DIST_DIR=${DIST_DIR}"
            "WORK_DIR=${PROOT_WORK_DIR}"
            "OUTPUT_DIR=${OUTPUT_ABI_DIR}"
            "CC=${CMAKE_C_COMPILER}"
            "AR_TOOL=${LLVM_AR}"
            "RANLIB_TOOL=${LLVM_RANLIB}"
            "STRIP_TOOL=${LLVM_STRIP}"
            "OBJCOPY_TOOL=${LLVM_OBJCOPY}"
            "OBJDUMP_TOOL=${LLVM_OBJDUMP}"
            "SYSROOT=${CMAKE_SYSROOT}"
            "MAKE_TOOL=${GNU_MAKE}"
            "HOST_PREFIX=$ENV{PREFIX}"
            sh "${CMAKE_CURRENT_SOURCE_DIR}/build-proot.sh"
        DEPENDS
            "${MUSL_ARCHIVE}"
            "${TALLOC_ARCHIVE}"
            "${PROOT_ARCHIVE}"
            ${PROOT_PATCHES}
        VERBATIM
        COMMENT "Building libproot.so and libproot-loader.so from source")

add_custom_target(proot_binaries ALL
        DEPENDS "${PROOT_OUTPUT}" "${PROOT_LOADER_OUTPUT}")

add_library(rftool SHARED
        librftool.c
        "${ZLIB_SRC_DIR}/adler32.c"
        "${ZLIB_SRC_DIR}/crc32.c"
        "${ZLIB_SRC_DIR}/gzclose.c"
        "${ZLIB_SRC_DIR}/gzlib.c"
        "${ZLIB_SRC_DIR}/gzread.c"
        "${ZLIB_SRC_DIR}/inffast.c"
        "${ZLIB_SRC_DIR}/inflate.c"
        "${ZLIB_SRC_DIR}/inftrees.c"
        "${ZLIB_SRC_DIR}/trees.c"
        "${ZLIB_SRC_DIR}/zutil.c")

target_include_directories(rftool PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${ZLIB_SRC_DIR}")

target_compile_definitions(rftool PRIVATE
        _FILE_OFFSET_BITS=64
        _GNU_SOURCE
        _LARGEFILE64_SOURCE=1
        NO_GZCOMPRESS)

target_compile_options(rftool PRIVATE
        -O2
        -Wall
        -Wextra)

add_dependencies(rftool proot_binaries)
