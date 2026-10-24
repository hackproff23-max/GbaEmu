#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <vector>
#include <string>
#include <cstring>
#include <cctype>
#include <algorithm>
#include "retro_min.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "gbabridge", __VA_ARGS__)
#define MAXW 256
#define MAXH 256

typedef bool (*env_t)(unsigned, void *);
typedef void (*video_t)(const void *, unsigned, unsigned, size_t);
typedef void (*asample_t)(int16_t, int16_t);
typedef size_t (*abatch_t)(const int16_t *, size_t);
typedef void (*poll_t)();
typedef int16_t (*istate_t)(unsigned, unsigned, unsigned, unsigned);

static void *g_lib = nullptr;
static void (*p_init)() = nullptr;
static void (*p_deinit)() = nullptr;
static void (*p_run)() = nullptr;
static void (*p_reset)() = nullptr;
static bool (*p_load_game)(const retro_game_info *) = nullptr;
static void (*p_unload_game)() = nullptr;
static void (*p_get_av)(retro_system_av_info *) = nullptr;
static void (*p_set_env)(env_t) = nullptr;
static void (*p_set_video)(video_t) = nullptr;
static void (*p_set_asample)(asample_t) = nullptr;
static void (*p_set_abatch)(abatch_t) = nullptr;
static void (*p_set_poll)(poll_t) = nullptr;
static void (*p_set_istate)(istate_t) = nullptr;
static void (*p_set_port)(unsigned, unsigned) = nullptr;
static size_t (*p_ser_size)() = nullptr;
static bool (*p_ser)(void *, size_t) = nullptr;
static bool (*p_unser)(const void *, size_t) = nullptr;
static void *(*p_mem_data)(unsigned) = nullptr;
static size_t (*p_mem_size)(unsigned) = nullptr;
static void (*p_cheat_reset)() = nullptr;
static void (*p_cheat_set)(unsigned, bool, const char *) = nullptr;

static bool g_inited = false, g_loaded = false;
static std::string g_sys, g_name;
static std::vector<uint8_t> g_rom;
static std::vector<int16_t> g_audio;
static uint8_t g_frame[MAXW * MAXH * 4];
static int g_w = 0, g_h = 0, g_fmt = 1;
static double g_fps = 60.0, g_rate = 32768.0;
static std::atomic<int> g_mask{0};
// Todo acesso ao núcleo passa por este mutex: a thread do jogo (run/áudio) e a da interface (estado, reset, unload)
// nunca mexem no núcleo ao mesmo tempo.
static std::mutex g_mu;
#define GUARD std::lock_guard<std::mutex> _lk(g_mu)

template <typename T> static bool sym(T &f, const char *n) { f = (T)dlsym(g_lib, n); return f != nullptr; }

static bool env_cb(unsigned cmd, void *data) {
    switch (cmd) {
        case 3: *(bool *)data = true; return true;            // GET_CAN_DUPE
        case 8: return true;                                  // SET_PERFORMANCE_LEVEL
        case 9: case 31: *(const char **)data = g_sys.c_str(); return true; // system/save dir
        case 10: { int f = *(int *)data; if (f == 1 || f == 2) { g_fmt = f; return true; } return false; }
        default: return false;
    }
}
static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || w > MAXW || h > MAXH) return;
    g_w = (int)w; g_h = (int)h;
    for (unsigned y = 0; y < h; y++) {
        const uint8_t *row = (const uint8_t *)data + y * pitch;
        uint8_t *d = g_frame + y * w * 4;
        if (g_fmt == 1) {
            const uint32_t *s = (const uint32_t *)row;
            for (unsigned x = 0; x < w; x++, d += 4) { uint32_t v = s[x]; d[0] = v >> 16; d[1] = v >> 8; d[2] = v; d[3] = 255; }
        } else {
            const uint16_t *s = (const uint16_t *)row;
            for (unsigned x = 0; x < w; x++, d += 4) {
                uint16_t v = s[x]; unsigned r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
                d[0] = (r << 3) | (r >> 2); d[1] = (g << 2) | (g >> 4); d[2] = (b << 3) | (b >> 2); d[3] = 255;
            }
        }
    }
}
static void asample_cb(int16_t l, int16_t r) { g_audio.push_back(l); g_audio.push_back(r); }
static size_t abatch_cb(const int16_t *d, size_t f) { g_audio.insert(g_audio.end(), d, d + f * 2); return f; }
static void poll_cb() {}
static int16_t istate_cb(unsigned port, unsigned dev, unsigned, unsigned id) {
    if (port != 0 || dev != RETRO_DEVICE_JOYPAD) return 0;
    int m = g_mask.load();
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)(m & 0xFFF);
    return id < 16 ? (int16_t)((m >> id) & 1) : 0;
}

static void unload_core() {
    if (g_loaded && p_unload_game) p_unload_game();
    if (g_inited && p_deinit) p_deinit();
    if (g_lib) dlclose(g_lib);
    g_lib = nullptr; g_loaded = g_inited = false; g_w = g_h = 0; g_audio.clear();
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_meu_gbaemu_Native_load(JNIEnv *e, jobject, jstring core, jbyteArray rom, jstring name, jstring sys) { GUARD;
    unload_core();
    const char *c = e->GetStringUTFChars(core, nullptr);
    g_lib = dlopen(c, RTLD_NOW | RTLD_LOCAL);
    if (!g_lib) LOG("dlopen falhou: %s", dlerror());
    e->ReleaseStringUTFChars(core, c);
    if (!g_lib) return JNI_FALSE;
    bool ok = sym(p_init, "retro_init") && sym(p_deinit, "retro_deinit") && sym(p_run, "retro_run") &&
              sym(p_reset, "retro_reset") && sym(p_load_game, "retro_load_game") && sym(p_unload_game, "retro_unload_game") &&
              sym(p_get_av, "retro_get_system_av_info") && sym(p_set_env, "retro_set_environment") &&
              sym(p_set_video, "retro_set_video_refresh") && sym(p_set_asample, "retro_set_audio_sample") &&
              sym(p_set_abatch, "retro_set_audio_sample_batch") && sym(p_set_poll, "retro_set_input_poll") &&
              sym(p_set_istate, "retro_set_input_state") && sym(p_set_port, "retro_set_controller_port_device") &&
              sym(p_ser_size, "retro_serialize_size") && sym(p_ser, "retro_serialize") && sym(p_unser, "retro_unserialize") &&
              sym(p_mem_data, "retro_get_memory_data") && sym(p_mem_size, "retro_get_memory_size");
    if (!ok) { LOG("símbolos libretro ausentes"); unload_core(); return JNI_FALSE; }
    // cheats são opcionais: se o núcleo não tiver, os ponteiros ficam nulos e as chamadas viram "nada"
    sym(p_cheat_reset, "retro_cheat_reset"); sym(p_cheat_set, "retro_cheat_set");
    const char *s = e->GetStringUTFChars(sys, nullptr); g_sys = s; e->ReleaseStringUTFChars(sys, s);
    const char *n = e->GetStringUTFChars(name, nullptr); g_name = n; e->ReleaseStringUTFChars(name, n);
    jsize len = e->GetArrayLength(rom);
    g_rom.resize(len);
    e->GetByteArrayRegion(rom, 0, len, (jbyte *)g_rom.data());
    g_fmt = 1;
    p_set_env(env_cb); p_set_video(video_cb); p_set_asample(asample_cb); p_set_abatch(abatch_cb);
    p_set_poll(poll_cb); p_set_istate(istate_cb);
    p_init(); g_inited = true;
    retro_game_info gi{g_name.c_str(), g_rom.data(), g_rom.size(), nullptr};
    if (!p_load_game(&gi)) { LOG("retro_load_game falhou"); unload_core(); return JNI_FALSE; }
    retro_system_av_info av; memset(&av, 0, sizeof(av)); p_get_av(&av);
    g_fps = av.timing.fps > 1 ? av.timing.fps : 60.0;
    g_rate = av.timing.sample_rate > 1 ? av.timing.sample_rate : 32768.0;
    p_set_port(0, RETRO_DEVICE_JOYPAD);
    g_loaded = true;
    return JNI_TRUE;
}
JNIEXPORT void JNICALL Java_com_meu_gbaemu_Native_run(JNIEnv *, jobject) { GUARD; if (g_loaded) p_run(); }
JNIEXPORT void JNICALL Java_com_meu_gbaemu_Native_reset(JNIEnv *, jobject) { GUARD; if (g_loaded) p_reset(); }
JNIEXPORT void JNICALL Java_com_meu_gbaemu_Native_setInput(JNIEnv *, jobject, jint m) { g_mask.store(m); }
JNIEXPORT jobject JNICALL Java_com_meu_gbaemu_Native_frameBuffer(JNIEnv *e, jobject) { return e->NewDirectByteBuffer(g_frame, sizeof(g_frame)); }
JNIEXPORT jintArray JNICALL Java_com_meu_gbaemu_Native_frameSize(JNIEnv *e, jobject) {
    jintArray a = e->NewIntArray(2); jint v[2] = {g_w, g_h}; e->SetIntArrayRegion(a, 0, 2, v); return a;
}
JNIEXPORT jint JNICALL Java_com_meu_gbaemu_Native_audio(JNIEnv *e, jobject, jshortArray out) { GUARD;
    size_t n = std::min((size_t)e->GetArrayLength(out), g_audio.size()); n &= ~(size_t)1;
    if (n) { e->SetShortArrayRegion(out, 0, (jsize)n, g_audio.data()); g_audio.erase(g_audio.begin(), g_audio.begin() + n); }
    return (jint)n;
}
JNIEXPORT void JNICALL Java_com_meu_gbaemu_Native_clearAudio(JNIEnv *, jobject) { GUARD; g_audio.clear(); }
JNIEXPORT jdouble JNICALL Java_com_meu_gbaemu_Native_sampleRate(JNIEnv *, jobject) { return g_rate; }
JNIEXPORT jdouble JNICALL Java_com_meu_gbaemu_Native_fps(JNIEnv *, jobject) { return g_fps; }
JNIEXPORT jbyteArray JNICALL Java_com_meu_gbaemu_Native_readSram(JNIEnv *e, jobject) { GUARD;
    if (!g_loaded) return nullptr;
    size_t sz = p_mem_size(0); void *d = p_mem_data(0);
    if (!sz || !d) return nullptr;
    jbyteArray a = e->NewByteArray((jsize)sz); e->SetByteArrayRegion(a, 0, (jsize)sz, (jbyte *)d); return a;
}
JNIEXPORT void JNICALL Java_com_meu_gbaemu_Native_writeSram(JNIEnv *e, jobject, jbyteArray a) { GUARD;
    if (!g_loaded) return;
    size_t sz = p_mem_size(0); void *d = p_mem_data(0);
    if (!sz || !d) return;
    size_t n = std::min(sz, (size_t)e->GetArrayLength(a));
    e->GetByteArrayRegion(a, 0, (jsize)n, (jbyte *)d);
}
JNIEXPORT jbyteArray JNICALL Java_com_meu_gbaemu_Native_saveState(JNIEnv *e, jobject) { GUARD;
    if (!g_loaded) return nullptr;
    size_t sz = p_ser_size(); if (!sz) return nullptr;
    std::vector<uint8_t> buf(sz);
    if (!p_ser(buf.data(), sz)) return nullptr;
    jbyteArray a = e->NewByteArray((jsize)sz); e->SetByteArrayRegion(a, 0, (jsize)sz, (jbyte *)buf.data()); return a;
}
JNIEXPORT jboolean JNICALL Java_com_meu_gbaemu_Native_loadState(JNIEnv *e, jobject, jbyteArray a) { GUARD;
    if (!g_loaded) return JNI_FALSE;
    jsize n = e->GetArrayLength(a); if (n < 1024) return JNI_FALSE; std::vector<uint8_t> buf(n);
    e->GetByteArrayRegion(a, 0, n, (jbyte *)buf.data());
    return p_unser(buf.data(), buf.size()) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_meu_gbaemu_Native_cheatReset(JNIEnv *, jobject) { GUARD; if (g_loaded && p_cheat_reset) p_cheat_reset(); }
JNIEXPORT void JNICALL Java_com_meu_gbaemu_Native_cheatSet(JNIEnv *e, jobject, jint idx, jboolean on, jstring code) { GUARD;
    if (!g_loaded || !p_cheat_set || !code) return;
    const char *c = e->GetStringUTFChars(code, nullptr);
    if (c) {
        // só aceita exatamente "XXXXXXXX XXXXXXXX" (hex): qualquer outra coisa nunca chega ao analisador do núcleo
        bool ok = strlen(c) == 17 && c[8] == ' ';
        for (int i = 0; ok && i < 17; i++) if (i != 8 && !isxdigit((unsigned char)c[i])) ok = false;
        if (ok) p_cheat_set((unsigned)idx, on == JNI_TRUE, c);
        e->ReleaseStringUTFChars(code, c);
    }
}
JNIEXPORT void JNICALL Java_com_meu_gbaemu_Native_unload(JNIEnv *, jobject) { GUARD; unload_core(); }

}
