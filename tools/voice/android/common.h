// Shared helpers for the voice benchmark harnesses (sherpa_bench, whisper_bench, vosk_bench).
// Each harness reads a list of 16 kHz mono 16-bit WAV files, decodes them, and prints one JSON line per file plus a
// final summary line with process CPU time and peak RSS (getrusage), which feed docs/voice-eval.md.
#pragma once
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

static inline double now_ms() {
  timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

static inline void sleep_until_ms(double t) {
  double d = t - now_ms();
  if (d > 0) usleep((useconds_t)(d * 1000));
}

// Minimal RIFF/WAVE reader: PCM 16-bit, mono, 16 kHz only (prepare.py writes exactly that).
static inline bool read_wav(const std::string &path, std::vector<float> &out) {
  std::ifstream f(path, std::ios::binary);
  if (!f) return false;
  char riff[12];
  f.read(riff, 12);
  if (!f || memcmp(riff, "RIFF", 4) || memcmp(riff + 8, "WAVE", 4)) return false;
  uint16_t fmt = 0, ch = 0, bits = 0;
  uint32_t sr = 0;
  while (f) {
    char id[4];
    uint32_t sz;
    f.read(id, 4);
    f.read((char *)&sz, 4);
    if (!f) return false;
    if (!memcmp(id, "fmt ", 4)) {
      std::vector<char> b(sz);
      f.read(b.data(), sz);
      memcpy(&fmt, &b[0], 2);
      memcpy(&ch, &b[2], 2);
      memcpy(&sr, &b[4], 4);
      memcpy(&bits, &b[14], 2);
    } else if (!memcmp(id, "data", 4)) {
      if (fmt != 1 || ch != 1 || bits != 16 || sr != 16000) return false;
      std::vector<int16_t> s(sz / 2);
      f.read((char *)s.data(), sz);
      out.resize(s.size());
      for (size_t i = 0; i < s.size(); ++i) out[i] = s[i] / 32768.0f;
      return true;
    } else {
      f.seekg(sz + (sz & 1), std::ios::cur);
    }
  }
  return false;
}

static inline std::vector<std::string> read_lines(const std::string &path) {
  std::vector<std::string> v;
  std::ifstream f(path);
  std::string s;
  while (std::getline(f, s))
    if (!s.empty()) v.push_back(s);
  return v;
}

static inline std::string json_escape(const char *s) {
  std::string o;
  for (; s && *s; ++s) {
    unsigned char c = *s;
    if (c == '"' || c == '\\') { o += '\\'; o += c; }
    else if (c == '\n') o += "\\n";
    else if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
    else o += c;
  }
  return o;
}

static inline std::string base_id(const std::string &path) {
  size_t a = path.find_last_of('/');
  std::string n = a == std::string::npos ? path : path.substr(a + 1);
  size_t d = n.rfind('.');
  return d == std::string::npos ? n : n.substr(0, d);
}

// CPU seconds (user+sys) of this process so far.
static inline double cpu_s() {
  rusage ru;
  getrusage(RUSAGE_SELF, &ru);
  return ru.ru_utime.tv_sec + ru.ru_utime.tv_usec / 1e6 + ru.ru_stime.tv_sec + ru.ru_stime.tv_usec / 1e6;
}

// Summary line: model load time and its CPU, then decode-phase wall/CPU (excluding the load) and peak RSS (kB).
// cpu_decode_s / audio_s is the energy proxy (CPU-seconds per second of speech), including any thread-pool spinning.
static double g_load_cpu = 0;
static inline void mark_loaded() { g_load_cpu = cpu_s(); }
static inline void print_rusage(const char *tag, double load_ms, double audio_s, double wall_ms) {
  rusage ru;
  getrusage(RUSAGE_SELF, &ru);
  printf("{\"summary\":\"%s\",\"load_ms\":%.1f,\"load_cpu_s\":%.3f,\"audio_s\":%.3f,\"wall_ms\":%.1f,"
         "\"cpu_decode_s\":%.3f,\"maxrss_kb\":%ld}\n",
         tag, load_ms, g_load_cpu, audio_s, wall_ms, cpu_s() - g_load_cpu, (long)ru.ru_maxrss);
  fflush(stdout);
}
