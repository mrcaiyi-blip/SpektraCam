#include "spektracam.h"

#include <android/bitmap.h>
#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>

namespace {
class Utf {
public:
    Utf(JNIEnv* env, jstring value)
        : env_(env), value_(value), chars_(value ? env->GetStringUTFChars(value, nullptr) : nullptr) {}
    ~Utf() { if (chars_) env_->ReleaseStringUTFChars(value_, chars_); }
    const char* data() const { return chars_; }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_;
};
}

extern "C" JNIEXPORT jstring JNICALL Java_org_spektracam_NativeFilm_render(
        JNIEnv* env, jclass, jobject raw, jint width, jint height, jint rowStride,
        jint pixelStride, jint bayer, jfloatArray black, jfloat white,
        jfloatArray wb, jfloatArray matrix, jfloatArray shading, jint shadingWidth,
        jint shadingHeight, jboolean highlights, jstring root, jstring film, jstring paper,
        jstring meter, jobject bitmap) {
    sc_raw_frame frame{};
    frame.data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(raw));
    const jlong bytes = env->GetDirectBufferCapacity(raw);
    if (!frame.data || bytes < 0 || width < 2 || height < 2 || rowStride < 1 || pixelStride < 2 ||
        !black || env->GetArrayLength(black) != 4 || !wb || env->GetArrayLength(wb) != 4 ||
        (matrix && env->GetArrayLength(matrix) != 9) ||
        (shading && (shadingWidth < 1 || shadingHeight < 1 || shadingWidth > 1024 ||
            shadingHeight > 1024 || env->GetArrayLength(shading) != shadingWidth * shadingHeight * 4))) {
        return env->NewStringUTF("Invalid Android RAW metadata");
    }
    frame.size = size_t(bytes);
    frame.width = uint32_t(width);
    frame.height = uint32_t(height);
    frame.row_stride = size_t(rowStride);
    frame.pixel_stride = size_t(pixelStride);
    frame.bayer = uint32_t(bayer);
    frame.white = white;
    frame.highlight_chroma_blend = highlights;
    env->GetFloatArrayRegion(black, 0, 4, frame.black);
    env->GetFloatArrayRegion(wb, 0, 4, frame.white_balance);
    if (matrix) {
        env->GetFloatArrayRegion(matrix, 0, 9, frame.matrix);
        frame.has_matrix = 1;
    }
    Utf rootValue(env, root), filmValue(env, film), paperValue(env, paper), meterValue(env, meter);
    if (env->ExceptionCheck()) return nullptr;
    AndroidBitmapInfo info{};
    void* rgba = nullptr;
    const size_t rowBytes = size_t(frame.width) * 4;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width != frame.width ||
        info.height != frame.height || info.stride < rowBytes ||
        AndroidBitmap_lockPixels(env, bitmap, &rgba) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return env->NewStringUTF("Invalid Android film output bitmap");
    }
    jfloat* gains = shading ? env->GetFloatArrayElements(shading, nullptr) : nullptr;
    if (shading && !gains) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return nullptr;
    }
    frame.lens_shading = gains;
    frame.shading_width = gains ? uint32_t(shadingWidth) : 0;
    frame.shading_height = gains ? uint32_t(shadingHeight) : 0;
    char error[512]{};
    const int success = sc_render_raw(&frame, rootValue.data(), filmValue.data(), paperValue.data(),
                                     meterValue.data(), static_cast<uint8_t*>(rgba),
                                     size_t(info.stride) * info.height, error, sizeof(error));
    if (success && info.stride != rowBytes) {
        // The core writes packed RGBA. Expand from the last row to keep unread
        // packed pixels intact when the bitmap allocator pads each row.
        auto* pixels = static_cast<uint8_t*>(rgba);
        for (size_t row = info.height - 1; row > 0; --row) {
            std::memmove(pixels + row * info.stride, pixels + row * rowBytes, rowBytes);
        }
    }
    if (gains) env->ReleaseFloatArrayElements(shading, gains, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, bitmap);
    return success ? nullptr : env->NewStringUTF(error);
}
