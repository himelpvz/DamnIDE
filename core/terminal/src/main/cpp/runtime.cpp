#include "runtime.hpp"

#include <cstring>

namespace {
RuntimeState g_state;
}

RuntimeState& state() {
    return g_state;
}

void set_last_error(const std::string& error) {
    std::lock_guard<std::mutex> lock(state().state_mutex);
    state().last_error = error;
}

std::string get_last_error() {
    std::lock_guard<std::mutex> lock(state().state_mutex);
    return state().last_error;
}

JNIEnv* get_env(bool* needs_detach) {
    *needs_detach = false;
    auto* vm = state().vm;
    if (vm == nullptr) {
        return nullptr;
    }

    JNIEnv* env = nullptr;
    const jint status = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
        *needs_detach = true;
    } else if (status != JNI_OK) {
        return nullptr;
    }
    return env;
}

static void emit_string(const char* method_name, const std::string& value) {
    if (state().bindings_class == nullptr) {
        return;
    }

    bool needs_detach = false;
    JNIEnv* env = get_env(&needs_detach);
    if (env == nullptr) {
        return;
    }

    const jmethodID method = env->GetStaticMethodID(state().bindings_class, method_name, "(Ljava/lang/String;)V");
    if (method != nullptr) {
        jstring payload = env->NewStringUTF(value.c_str());
        env->CallStaticVoidMethod(state().bindings_class, method, payload);
        env->DeleteLocalRef(payload);
    }

    if (needs_detach) {
        state().vm->DetachCurrentThread();
    }
}

void emit_output(const std::string& value) {
    emit_string("dispatchOutput", value);
}

void emit_error(const std::string& value) {
    emit_string("dispatchError", value);
}

void emit_exit(jint code) {
    if (state().bindings_class == nullptr) {
        return;
    }

    bool needs_detach = false;
    JNIEnv* env = get_env(&needs_detach);
    if (env == nullptr) {
        return;
    }

    const jmethodID method = env->GetStaticMethodID(state().bindings_class, "dispatchExit", "(I)V");
    if (method != nullptr) {
        env->CallStaticVoidMethod(state().bindings_class, method, code);
    }

    if (needs_detach) {
        state().vm->DetachCurrentThread();
    }
}
