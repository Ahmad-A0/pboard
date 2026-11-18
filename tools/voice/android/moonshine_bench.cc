// Benchmark for Moonshine Voice streaming models (MIT) through libmoonshine's C API, same output format as
// sherpa_bench (online mode).
//   moonshine_bench --model DIR --arch 2|4|5 [--paced] [--chunk-ms 100] [--update-ms 500] list.txt
// Audio is added in --chunk-ms pieces; every --update-ms of audio the transcript is refreshed (FORCE_UPDATE), as the
// library's own streaming helpers do. At the end the stream is stopped and drained; final_ms is that drain.
#include "common.h"
#include "moonshine-c-api.h"

#include <cstdlib>
#include <map>

static std::string join(const transcript_t *t) {
  std::string s;
  for (uint64_t i = 0; t && i < t->line_count; ++i) {
    const char *x = t->lines[i].text;
    if (!x || !*x) continue;
    std::string l = x;
    size_t a = l.find_first_not_of(' ');
    if (a == std::string::npos) continue;
    s += (s.empty() ? "" : " ") + l.substr(a);
  }
  return s;
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
  const int update = 16 * atoi(get("--update-ms", "500").c_str());
  const int tail = 16 * atoi(get("--tail-ms", "1000").c_str());
  double t0 = now_ms(), audio_s = 0;
  int32_t tr = moonshine_load_transcriber_from_files(get("--model", "").c_str(), atoi(get("--arch", "2").c_str()),
                                                     nullptr, 0, MOONSHINE_HEADER_VERSION);
  if (tr < 0) { fprintf(stderr, "load failed: %s\n", moonshine_error_to_string(tr)); return 1; }
  mark_loaded();
  double load_ms = now_ms() - t0, wall0 = now_ms();
  for (auto &path : read_lines(list)) {
    std::vector<float> x;
    if (!read_wav(path, x)) { fprintf(stderr, "bad wav %s\n", path.c_str()); continue; }
    audio_s += x.size() / 16000.0;
    const size_t speech = x.size();
    if (paced) x.resize(speech + tail, 0.f);  // real-time trailing silence, as in sherpa_bench
    int32_t st = moonshine_create_stream(tr, 0);
    moonshine_start_stream(tr, st);
    double first_partial = -1, compute = 0, start = now_ms();
    std::string last, hist;
    int since = 0;
    for (size_t off = 0; off < x.size(); off += chunk) {
      size_t n = std::min((size_t)chunk, x.size() - off);
      if (paced) sleep_until_ms(start + (off + n) / 16.0);
      double c0 = now_ms();
      moonshine_transcribe_add_audio_to_stream(tr, st, x.data() + off, n, 16000, 0);
      since += n;
      if (since >= update) {
        since = 0;
        transcript_t *t = nullptr;
        moonshine_transcribe_stream(tr, st, MOONSHINE_FLAG_FORCE_UPDATE, &t);
        std::string cur = join(t);
        double c1 = now_ms();
        if (first_partial < 0 && !cur.empty()) first_partial = paced ? c1 - start : (off + n) / 16.0 + (c1 - c0);
        if (paced && cur != last) {
          char b[48];
          snprintf(b, sizeof b, "[%.0f,%.0f,\"", (off + n) / 16.0, c1 - start);
          hist += (hist.empty() ? "" : ",") + std::string(b) + json_escape(cur.c_str()) + "\"]";
        }
        last = cur;
      }
      if (off < speech) compute += now_ms() - c0;
    }
    double f0 = now_ms();
    moonshine_stop_stream(tr, st);
    transcript_t *t = nullptr;
    moonshine_transcribe_stream(tr, st, 0, &t);
    std::string text = join(t);
    double f1 = now_ms();
    printf("{\"id\":\"%s\",\"dur\":%.3f,\"text\":\"%s\",\"decode_ms\":%.1f,\"flush_ms\":%.1f,\"final_ms\":%.1f,"
           "\"first_partial_ms\":%.1f,\"tail_ms\":%d%s%s%s}\n",
           base_id(path).c_str(), speech / 16000.0, json_escape(text.c_str()).c_str(), compute, f1 - f0,
           paced ? f1 - (start + speech / 16.0) : f1 - f0, first_partial, paced ? tail / 16 : 0,
           paced ? ",\"partials\":[" : "", hist.c_str(), paced ? "]" : "");
    fflush(stdout);
    moonshine_free_stream(tr, st);
  }
  print_rusage("moonshine", load_ms, audio_s, now_ms() - wall0);
  moonshine_free_transcriber(tr);
  return 0;
}
