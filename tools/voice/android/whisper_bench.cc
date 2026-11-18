// Benchmark for whisper.cpp (MIT): decode each WAV in a list, greedy, English, no timestamps.
//   whisper_bench --model ggml-base.en-q5_1.bin [--threads 2] [--audio-ctx 0|dyn|N] [--beam N] list.txt
// --audio-ctx dyn sizes the encoder context to the utterance (50 frames/s + 1 s margin, multiple of 64) instead of the
// fixed 30 s window: much faster for short dictation, at some accuracy risk for models not fine-tuned for it.
// whisper has no streaming mode: decode_ms is both compute and the final latency after end of speech.
#include "common.h"
#include "whisper.h"

#include <cmath>
#include <cstdlib>
#include <map>

int main(int argc, char **argv) {
  std::map<std::string, std::string> a;
  std::string list;
  for (int i = 1; i < argc; ++i) {
    std::string k = argv[i];
    if (k.rfind("--", 0) == 0 && i + 1 < argc) a[k] = argv[++i];
    else list = k;
  }
  auto get = [&](const char *k, const char *d) { return a.count(k) ? a[k] : std::string(d); };
  const int threads = atoi(get("--threads", "2").c_str());
  const std::string actx = get("--audio-ctx", "0");
  const int beam = atoi(get("--beam", "0").c_str());
  whisper_log_set([](enum ggml_log_level, const char *, void *) {}, nullptr);

  double t0 = now_ms(), audio_s = 0;
  whisper_context_params cp = whisper_context_default_params();
  cp.use_gpu = false;
  whisper_context *ctx = whisper_init_from_file_with_params(get("--model", "").c_str(), cp);
  if (!ctx) { fprintf(stderr, "failed to load model\n"); return 1; }
  mark_loaded();
  double load_ms = now_ms() - t0, wall0 = now_ms();
  for (auto &path : read_lines(list)) {
    std::vector<float> x;
    if (!read_wav(path, x)) { fprintf(stderr, "bad wav %s\n", path.c_str()); continue; }
    double dur = x.size() / 16000.0;
    audio_s += dur;
    whisper_full_params p = whisper_full_default_params(beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beam > 1) p.beam_search.beam_size = beam;
    p.n_threads = threads;
    p.language = "en";
    p.translate = false;
    p.no_timestamps = true;
    p.single_segment = false;
    p.print_progress = p.print_realtime = p.print_timestamps = p.print_special = false;
    p.no_context = true;
    if (actx == "dyn") p.audio_ctx = std::min(1500, (int)std::ceil((dur + 1.0) * 50 / 64.0) * 64);
    else p.audio_ctx = atoi(actx.c_str());
    // whisper pads input shorter than 1 s; keep as is.
    double c0 = now_ms();
    int rc = whisper_full(ctx, p, x.data(), (int)x.size());
    double c1 = now_ms();
    std::string text;
    if (rc == 0)
      for (int i = 0; i < whisper_full_n_segments(ctx); ++i) text += whisper_full_get_segment_text(ctx, i);
    printf("{\"id\":\"%s\",\"dur\":%.3f,\"text\":\"%s\",\"decode_ms\":%.1f,\"final_ms\":%.1f,\"rc\":%d}\n",
           base_id(path).c_str(), dur, json_escape(text.c_str()).c_str(), c1 - c0, c1 - c0, rc);
    fflush(stdout);
  }
  print_rusage("whisper", load_ms, audio_s, now_ms() - wall0);
  whisper_free(ctx);
  return 0;
}
