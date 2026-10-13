cmake_minimum_required(VERSION 3.22)
project(gitphindroid C)

# The Gitphin core (core/, copied unchanged by sync_core.sh) on top of the port layer (port/: include, platform, net, server, features, web).
# Android: libgitphin.so for the app (JNI). Linux: gitphind, the same server as a program, for tests.
option(GITPHIN_ANDROID "Build the JNI library for the Android app" OFF)
option(GITPHIN_SANITIZE "Linux: build with ASan and UBSan" OFF)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)

set(CORE ${CMAKE_CURRENT_SOURCE_DIR}/core)
set(PORT ${CMAKE_CURRENT_SOURCE_DIR}/port)

# The core: hub_http.c and the resident files, then the plugins that are linked in (platform/plugin.c)
set(CORE_SOURCES
    # unit files only: they #include their parts (src/README.md upstream); parts are never compiled alone
    ${CORE}/hub_http.c ${CORE}/hub_chunk.c ${CORE}/hub_git.c ${CORE}/hub_gitview.c
    ${CORE}/hub_gitobj.c ${CORE}/hub_gitio_furi.c ${CORE}/hub_inflate.c
    ${CORE}/hub_dav.c
    ${CORE}/hub_gitpack.c ${CORE}/hub_gitgraph.c ${CORE}/hub_deflate.c ${CORE}/hub_sha1.c ${CORE}/hub_gitdocutil.c
    ${CORE}/hub_gitext.c ${CORE}/hub_gitcommit.c ${CORE}/hub_gitwrite.c ${CORE}/hub_gitwrite_furi.c ${CORE}/hub_gitdiff.c
    ${CORE}/hub_gitsearch.c
    ${CORE}/hub_gitdoc.c
)

# port/platform: furi, Storage and the plugin table on POSIX
set(PLATFORM_SOURCES ${PORT}/platform/furi.c ${PORT}/platform/storage.c ${PORT}/platform/plugin.c)

# port/net: the TCP transport (net_int.h lists the files)
set(NET_SOURCES
    ${PORT}/net/transport.c ${PORT}/net/acceptor.c ${PORT}/net/auth.c ${PORT}/net/peer.c ${PORT}/net/pc_routes.c
)

# port/server: the worker and the hooks sync_core.sh puts into hub_http.c (the JNI or the host main are per target)
set(SERVER_SOURCES ${PORT}/server/server.c ${PORT}/server/hooks.c)

# port/features: what the port adds to the core
set(FEATURE_SOURCES
    # ZIP download (zip_int.h)
    ${PORT}/features/zip/zip_route.c ${PORT}/features/zip/zip_writer.c ${PORT}/features/zip/zip_walk.c
    ${PORT}/features/webhook/webhook.c
    ${PORT}/features/avatar/avatar.c
    # smart HTTP (features/smart/smart.h lists the files)
    ${PORT}/features/smart/route.c ${PORT}/features/smart/body.c ${PORT}/features/smart/out.c
    ${PORT}/features/smart/refs.c ${PORT}/features/smart/util.c ${PORT}/features/smart/odb.c
    ${PORT}/features/smart/odb_loose.c ${PORT}/features/smart/upload_pack.c ${PORT}/features/smart/upload_walk.c
    ${PORT}/features/smart/upload_shallow.c ${PORT}/features/smart/pack_writer.c ${PORT}/features/smart/receive_pack.c
    ${PORT}/features/smart/index_pack.c ${PORT}/features/smart/index_write.c
)

set(SOURCES ${CORE_SOURCES} ${PLATFORM_SOURCES} ${NET_SOURCES} ${SERVER_SOURCES} ${FEATURE_SOURCES})

# port/web/*.js (joined in this order) -> a C string literal (gen/web/extra_js.inc), served as /port/extra.js
set(GEN ${CMAKE_CURRENT_BINARY_DIR}/gen)
set(WEB_JS core phone_texts)
set(EXTRA_JS "")
foreach(part ${WEB_JS})
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${PORT}/web/${part}.js)
    file(READ ${PORT}/web/${part}.js PART_JS)
    string(APPEND EXTRA_JS "${PART_JS}\n")
endforeach()
string(REPLACE "\\" "\\\\" EXTRA_JS "${EXTRA_JS}")
string(REPLACE "\"" "\\\"" EXTRA_JS "${EXTRA_JS}")
string(REPLACE "\n" "\\n\"\n\"" EXTRA_JS "${EXTRA_JS}")
file(WRITE ${GEN}/web/extra_js.inc "\"${EXTRA_JS}\"\n")

function(gitphin_setup target)
    target_include_directories(${target} PRIVATE ${PORT}/include ${PORT}/server ${CORE} ${GEN})
    # HUB_MEM_RAW: no guarded allocator (it is for the 256 KB heap of the Flipper; armv7 defines __arm__).
    # HUB_EXT_QUERY + HUB_EXT_WRITE: hub_gitext.c holds the routes of both plugins.
    # EXT_BODY_MAX: commit-file / web upload size (64 KB on the Flipper; sync_core.sh makes it settable).
    target_compile_definitions(${target} PRIVATE HUB_MEM_RAW HUB_EXT_QUERY HUB_EXT_WRITE _GNU_SOURCE
        "EXT_BODY_MAX=(32*1024*1024)")
    target_compile_options(${target} PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-unused-function
        -Wno-missing-field-initializers -Wno-sign-compare -fno-strict-aliasing)
endfunction()

if(GITPHIN_ANDROID)
    add_library(gitphin SHARED ${SOURCES} ${PORT}/server/jni.c)
    gitphin_setup(gitphin)
    target_link_libraries(gitphin log)
    # Optimized also in debug APKs: inflate, deflate, SHA-1 and diff are CPU work (AGP debug is -O0).
    target_compile_options(gitphin PRIVATE -O2)
    # 16 KB page size devices (Android 15+)
    target_link_options(gitphin PRIVATE -Wl,-z,max-page-size=16384)
else()
    add_executable(gitphind ${SOURCES} ${PORT}/server/host_main.c)
    gitphin_setup(gitphind)
    find_package(Threads REQUIRED)
    target_link_libraries(gitphind Threads::Threads)
    if(GITPHIN_SANITIZE)
        target_compile_options(gitphind PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer -g)
        target_link_options(gitphind PRIVATE -fsanitize=address,undefined)
    endif()
endif()
