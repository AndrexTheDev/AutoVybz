# AutoVybz native measurement engine: Oboe full-duplex capture/playback + FFT.
# Same reproducibility flags as the upstream eq314hrir library (F-Droid).
find_package(oboe REQUIRED CONFIG)

add_library(autovybz SHARED
    avz_fft.cpp
    measurement_engine.cpp
    avz_jni.cpp)

set_target_properties(autovybz PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_options(autovybz PRIVATE -O3 -Wall -Wextra -fno-ident)
target_link_libraries(autovybz oboe::oboe log)
target_link_options(autovybz PRIVATE -Wl,--build-id=none -Wl,-z,max-page-size=16384)
