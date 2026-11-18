// Benchmark for Vosk (Apache-2.0) through libvosk's C API, same output format as sherpa_bench (online mode).
//   vosk_bench --model DIR [--paced] [--chunk-ms 100] list.txt
// Vosk segments at its own endpoints; segment texts are joined for the utterance hypothesis.
#include "common.h"
#include "vosk_api.h"

#include <cstdlib>
#include <map>

static std::string field(const char *json, const char *key) {
  std::string j = json ? json : "", k = std::string("\"") + key + "\"";
  size_t p = j.find(k);
  if (p == std::string::npos) return "";
  p = j.find('"', j.find(':', p + k.size()) + 1);
  size_t e = j.find('"', p + 1);
  return p == std::string::npos || e == std::string::npos ? "" : j.substr(p + 1, e - p - 1);
}

int main(int argc, char **argv) {
  std::map<std::string, std::string> a;
  std::string list;
  for (int i = 1; i < argc; ++i) {
    std::string k = argv[i];
    if (k == "--paced") a[k] = "1";
    else if (k.rfind("--", 0) == 0 && i + 1 < argc) a[k] = argv[++i];
    else list = k;
  }
  auto get = [&](const char *k, const char *d) { return a.count(k) ? a[k] : std::string(d); };
  const bool paced = a.count("--paced");
  const int chunk = 16 * atoi(get("--chunk-ms", "100").c_str());
  const int tail = 16 * atoi(get("--tail-ms", "1000").c_str());
  vosk_set_log_level(-1);
  double t0 = now_ms(), audio_s = 0;
  VoskModel *m = vosk_model_new(get("--model", "").c_str());
  if (!m) { fprintf(stderr, "failed to load model\n"); return 1; }
  mark_loaded();
  double load_ms = now_ms() - t0, wall0 = now_ms();
  for (auto &path : read_lines(list)) {
    std::vector<float> x;
    if (!read_wav(path, x)) { fprintf(stderr, "bad wav %s\n", path.c_str()); continue; }
    audio_s += x.size() / 16000.0;
    const size_t speech = x.size();
    if (paced) x.resize(speech + tail, 0.f);  // real-time trailing silence, as in sherpa_bench
    std::vector<short> s(x.size());
    for (size_t i = 0; i < x.size(); ++i) s[i] = (short)(x[i] * 32767.f);
    VoskRecognizer *r = vosk_recognizer_new(m, 16000.f);
    std::string text, last, hist;
    double first_partial = -1, compute = 0, start = now_ms();
    for (size_t off = 0; off < s.size(); off += chunk) {
      int n = (int)std::min((size_t)chunk, s.size() - off);
      if (paced) sleep_until_ms(start + (off + n) / 16.0);
      double c0 = now_ms();
      std::string cur;
      if (vosk_recognizer_accept_waveform_s(r, s.data() + off, n)) {
        std::string seg = field(vosk_recognizer_result(r), "text");
        if (!seg.empty()) text += (text.empty() ? "" : " ") + seg;
        cur = text;
      } else {
        std::string p = field(vosk_recognizer_partial_result(r), "partial");
        cur = text + (text.empty() || p.empty() ? "" : " ") + p;
      }
      double c1 = now_ms();
      if (off < speech) compute += c1 - c0;
      if (first_partial < 0 && !cur.empty()) first_partial = paced ? c1 - start : (off + n) / 16.0 + (c1 - c0);
      if (paced && cur != last) {
        char b[48];
        snprintf(b, sizeof b, "[%.0f,%.0f,\"", (off + n) / 16.0, c1 - start);
        hist += (hist.empty() ? "" : ",") + std::string(b) + json_escape(cur.c_str()) + "\"]";
      }
      last = cur;
    }
    double f0 = now_ms();
    std::string seg = field(vosk_recognizer_final_result(r), "text");
    double f1 = now_ms();
    if (!seg.empty()) text += (text.empty() ? "" : " ") + seg;
    printf("{\"id\":\"%s\",\"dur\":%.3f,\"text\":\"%s\",\"decode_ms\":%.1f,\"flush_ms\":%.1f,\"final_ms\":%.1f,"
           "\"first_partial_ms\":%.1f,\"tail_ms\":%d%s%s%s}\n",
           base_id(path).c_str(), speech / 16000.0, json_escape(text.c_str()).c_str(), compute, f1 - f0,
           paced ? f1 - (start + speech / 16.0) : f1 - f0, first_partial, paced ? tail / 16 : 0,
           paced ? ",\"partials\":[" : "", hist.c_str(), paced ? "]" : "");
    fflush(stdout);
    vosk_recognizer_free(r);
  }
  print_rusage("vosk", load_ms, audio_s, now_ms() - wall0);
  vosk_model_free(m);
  return 0;
}
