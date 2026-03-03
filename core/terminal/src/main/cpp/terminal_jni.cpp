#include "runtime.hpp"

#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <string>

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
    jstring rootfs_path) {
    if (rootfs_path == nullptr) {
        set_last_error("rootfsPath cannot be null");
        return -1;
    }

    if (state().reader_thread.joinable()) {
        state().reader_thread.join();
    }

    std::lock_guard<std::mutex> lock(state().state_mutex);
    if (state().running.load()) {
        set_last_error("Shell is already running");
        return -1;
    }

    const char* path_chars = env->GetStringUTFChars(rootfs_path, nullptr);
    if (path_chars == nullptr) {
        set_last_error("Failed to decode rootfsPath");
        return -1;
    }

    const std::string rootfs(path_chars);
    env->ReleaseStringUTFChars(rootfs_path, path_chars);

    const std::string shell_path = rootfs + "/bin/sh";
    if (access(shell_path.c_str(), X_OK) != 0) {
        set_last_error("Invalid rootfs: missing executable /bin/sh");
        return -1;
    }

    int master_fd = -1;
    int slave_fd = -1;
    if (open_pty_pair(&master_fd, &slave_fd) < 0) {
        set_last_error(std::string("PTY allocation failed: ") + std::strerror(errno));
        return -1;
    }

    pid_t pid = -1;
    if (start_shell_process(rootfs, master_fd, slave_fd, &pid) < 0) {
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
