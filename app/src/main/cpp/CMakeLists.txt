cmake_minimum_required(VERSION 3.22.1)

project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_subdirectory(OpenXR-SDK)
add_subdirectory(patchelf)

# Add Winlator shared library
add_library(winlator SHARED
        xr/engine.c
        xr/framebuffer.c
        xr/input.c
        xr/main.c
        xr/math.c
        xr/renderer.c
        winlator/drawable.c
        winlator/gpu_image.c
        winlator/sysvshared_memory.c
        winlator/xconnector_epoll.c
        winlator/alsa_client.c
        winlator/patchelf_wrapper.cpp
        winlator/vulkan.cpp
)

target_link_libraries(winlator
        log
        android
        jnigraphics
        openxr_loader
        aaudio
        EGL
        GLESv2
        GLESv3
)


# ---- EVSHIM (phase-0 stub) -----------------------------------
add_library(evshim SHARED winlator/evshim.c)

# ---------- SDL2 library (Build from source) ----------
# Add SDL2 as a library target.
# This assumes cpp/sdl2 has its own CMakeLists.txt or you list its sources here.
# Most commonly, SDL2 comes with its own CMake build system.
# If cpp/sdl2 has a CMakeLists.txt:
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
if(ANDROID)
  set(SDL_VIDEO ON CACHE BOOL "" FORCE)
  set(SDL_AUDIO ON CACHE BOOL "" FORCE)
  set(SDL_HAPTIC ON CACHE BOOL "" FORCE)
  set(SDL_JOYSTICK ON CACHE BOOL "" FORCE)
  set(SDL_POWER ON CACHE BOOL "" FORCE)
  set(SDL_SENSOR ON CACHE BOOL "" FORCE)
  set(SDL_LOADSO ON CACHE BOOL "" FORCE)
  set(SDL_DUMMYAUDIO OFF CACHE BOOL "" FORCE)
  set(SDL_DUMMYVIDEO OFF CACHE BOOL "" FORCE)
endif()
add_subdirectory(sdl2)

# If you need to list SDL2 source files manually (less ideal if SDL2 is complex):
# file(GLOB SDL2_SOURCES "sdl2/*.c" "sdl2/src/*/*.c" ...) # Adjust glob patterns
# add_library(MySDL2 STATIC ${SDL2_SOURCES}) # Or SHARED
# target_include_directories(MySDL2 PUBLIC "${CMAKE_CURRENT_LIST_DIR}/sdl2/include") # Assuming headers are in sdl2/include


# Tell evshim's compiler where to find SDL2 headers (still needed)
target_include_directories(evshim PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/sdl2/include" # Adjust if SDL headers are elsewhere in your sdl2 dir
)

# ---------- link everything ----------
target_link_libraries(evshim PRIVATE # Or PUBLIC
        SDL2::SDL2
        # Or MySDL2     # If you used add_library(MySDL2 ...)
        # Or SDL2::SDL2 # Common imported target name for modern CMake SDL2
        log
        dl
)

# drop .so into jniLibs/<abi> so Gradle packs it automatically
set_target_properties(evshim PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY
        "${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}")
