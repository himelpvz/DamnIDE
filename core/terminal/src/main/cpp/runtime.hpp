#pragma once

#include <jni.h>
#include <sys/types.h>
#include <atomic>
#include <mutex>
#include <string>
#include <thread>

struct RuntimeState {
    JavaVM* vm = nullptr;
    jclass bindings_class = nullptr;

    std::mutex state_mutex;
    std::mutex io_mutex;

    int master_fd = -1;
    pid_t child_pid = -1;
    std::thread reader_thread;
    std::atomic<bool> running{false};
    std::string last_error;
};

RuntimeState& state();

void set_last_error(const std::string& error);
std::string get_last_error();
JNIEnv* get_env(bool* needs_detach);
void emit_output(const std::string& value);
void emit_error(const std::string& value);
void emit_exit(jint code);

int open_pty_pair(int* master_fd, int* slave_fd);
int start_shell_process(const std::string& rootfs_path, int master_fd, int slave_fd, pid_t* child_pid);
void reader_loop();
void stop_process_by_pid(pid_t child_pid);
