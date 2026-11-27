cmake_minimum_required(VERSION 3.22.1)
project(karaokeaudio)

# Oboe is pulled in as a Prefab package from com.google.oboe:oboe.
# AGP generates the oboeConfig.cmake that find_package consumes.
find_package(oboe REQUIRED CONFIG)

add_library(karaokeaudio SHARED
  KaraokeOboeEngine.cpp
  KaraokeOboeJni.cpp
)

target_include_directories(karaokeaudio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(karaokeaudio PRIVATE cxx_std_17)

target_link_libraries(karaokeaudio
  oboe::oboe
  android
  log
  atomic
)

# 16 KB page alignment for Android 15+ devices. Oboe 1.10's prefab binary is
# already aligned; this covers the StageMic library itself.
target_link_options(karaokeaudio PRIVATE "-Wl,-z,max-page-size=16384")
