#include "runtime.hpp"

#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <string>
#include <vector>

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    state().vm = vm;

    bool needs_detach = false;
    JNIEnv* env = get_env(&needs_detach);
    if (env == nullptr) {
        return JNI_ERR;
    }

    jclass local_class = env->FindClass("com/hypex/damnide/core/terminal/pty/NativeTerminalBindings");
    if (local_class == nullptr) {
        return JNI_ERR;
    }

    state().bindings_class = reinterpret_cast<jclass>(env->NewGlobalRef(local_class));
    env->DeleteLocalRef(local_class);

    if (needs_detach) {
        state().vm->DetachCurrentThread();
    }

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hypex_damnide_core_terminal_pty_NativeTerminalBindings_nativeStartShell(
    JNIEnv* env,
    jobject,
    jstring proot_binary_path,
    jobjectArray args) {
    if (proot_binary_path == nullptr || args == nullptr) {
        set_last_error("proot path and args cannot be null");
        return -1;
    }

    if (state().reader_thread.joinable()) {
        state().reader_thread.join();
    }

    bool is_running = false;
    {
        std::lock_guard<std::mutex> lock(state().state_mutex);
        is_running = state().running.load();
    }
    if (is_running) {
        set_last_error("Shell is already running");
        return -1;
    }

    const char* proot_chars = env->GetStringUTFChars(proot_binary_path, nullptr);
    if (proot_chars == nullptr) {
        set_last_error("Failed to decode prootBinaryPath");
        return -1;
    }

    const std::string proot_path(proot_chars);
    env->ReleaseStringUTFChars(proot_binary_path, proot_chars);

    if (access(proot_path.c_str(), X_OK) != 0) {
        set_last_error("Invalid proot binary path or not executable");
        return -1;
    }

    const jsize args_len = env->GetArrayLength(args);
    std::vector<std::string> proot_args;
    proot_args.reserve(static_cast<size_t>(args_len));

    for (jsize i = 0; i < args_len; ++i) {
        auto* arg_obj = static_cast<jstring>(env->GetObjectArrayElement(args, i));
        if (arg_obj == nullptr) {
            set_last_error("Proot args cannot contain null values");
            return -1;
        }

        const char* arg_chars = env->GetStringUTFChars(arg_obj, nullptr);
        if (arg_chars == nullptr) {
            env->DeleteLocalRef(arg_obj);
            set_last_error("Failed to decode proot arg");
            return -1;
        }

        proot_args.emplace_back(arg_chars);
        env->ReleaseStringUTFChars(arg_obj, arg_chars);
        env->DeleteLocalRef(arg_obj);
    }

    int master_fd = -1;
    int slave_fd = -1;
    if (open_pty_pair(&master_fd, &slave_fd) < 0) {
        set_last_error(std::string("PTY allocation failed: ") + std::strerror(errno));
        return -1;
    }

    pid_t pid = -1;
    if (start_shell_process(proot_path, proot_args, master_fd, slave_fd, &pid) < 0) {
        close(master_fd);
        close(slave_fd);
        set_last_error(std::string("fork failed: ") + std::strerror(errno));
        return -1;
    }

    close(slave_fd);

    const int flags = fcntl(master_fd, F_GETFL, 0);
    fcntl(master_fd, F_SETFL, flags | O_NONBLOCK);

    state().master_fd = master_fd;
    state().child_pid = pid;
    state().running.store(true);
    state().last_error.clear();

    state().reader_thread = std::thread(reader_loop);

    return static_cast<jint>(master_fd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hypex_damnide_core_terminal_pty_NativeTerminalBindings_nativeWrite(
    JNIEnv* env,
    jobject,
    jint master_fd,
    jstring input) {
    if (input == nullptr || master_fd < 0 || !state().running.load()) {
        return;
    }

    const char* data = env->GetStringUTFChars(input, nullptr);
    if (data == nullptr) {
        return;
    }

    const jsize len = env->GetStringUTFLength(input);
    ssize_t written = 0;
    while (written < len) {
        std::lock_guard<std::mutex> io_lock(state().io_mutex);
        const ssize_t chunk = write(master_fd, data + written, static_cast<size_t>(len - written));
        if (chunk < 0) {
            if (errno == EINTR) {
                continue;
            }
            set_last_error(std::string("write failed: ") + std::strerror(errno));
            emit_error(get_last_error());
            break;
        }
        written += chunk;
    }

    env->ReleaseStringUTFChars(input, data);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hypex_damnide_core_terminal_pty_NativeTerminalBindings_nativeResize(
    JNIEnv*,
    jobject,
    jint master_fd,
    jint cols,
    jint rows) {
    if (master_fd < 0 || !state().running.load()) {
        return;
    }

    winsize ws{};
    ws.ws_col = static_cast<unsigned short>(cols);
    ws.ws_row = static_cast<unsigned short>(rows);

    std::lock_guard<std::mutex> io_lock(state().io_mutex);
    if (ioctl(master_fd, TIOCSWINSZ, &ws) < 0) {
        set_last_error(std::string("resize failed: ") + std::strerror(errno));
        emit_error(get_last_error());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hypex_damnide_core_terminal_pty_NativeTerminalBindings_nativeStop(
    JNIEnv*,
    jobject,
    jint child_pid) {
    stop_process_by_pid(static_cast<pid_t>(child_pid));

    if (state().reader_thread.joinable()) {
        state().reader_thread.join();
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hypex_damnide_core_terminal_pty_NativeTerminalBindings_nativeChildPid(
    JNIEnv*,
    jobject,
    jint master_fd) {
    if (master_fd == state().master_fd) {
        return static_cast<jint>(state().child_pid);
    }
    return -1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hypex_damnide_core_terminal_pty_NativeTerminalBindings_nativeLastError(
    JNIEnv* env,
    jobject) {
    const std::string error = get_last_error();
    if (error.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(error.c_str());
}
