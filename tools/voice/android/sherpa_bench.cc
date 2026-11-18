// On-device benchmark for sherpa-onnx (Apache-2.0) models through its C API.
//
//   sherpa_bench --mode online|offline [model flags] [--threads N] [--paced] [--chunk-ms 100] [--tail-ms 1000 paced / 2000 unpaced]
//                [--partials] [--decoding greedy_search] list.txt
//
// Model flags: --encoder E --decoder D --joiner J (transducer), --nemo-ctc M, --zipformer2-ctc M,
//              --moonshine-preprocessor/--moonshine-encoder/--moonshine-uncached/--moonshine-cached/--moonshine-merged,
//              --tokens T, --model-type X.
// online, unpaced: audio is fed in --chunk-ms pieces as fast as possible (decode after each piece). decode_ms is the
//   total compute for the utterance (RTF = decode_ms / dur); final_ms is the compute after the last real audio piece
//   (tail padding + input-finished + flush), i.e. the final-result latency after end of speech, excluding endpointing.
// online, --paced: pieces are released on a real-time clock, as a microphone would deliver them. first_partial_ms is
//   the wall time (from the start of audio) at which the first non-empty partial was available; final_ms as above.
// offline: the whole utterance is decoded after it ends; decode_ms is then also the final latency.
#include "common.h"
#include "sherpa-onnx/c-api/c-api.h"

#include <cstdlib>
#include <map>

int main(int argc, char **argv) {
  std::map<std::string, std::string> a;
  std::string list;
  for (int i = 1; i < argc; ++i) {
    std::string k = argv[i];
    if (k == "--paced" || k == "--partials") a[k] = "1";
    else if (k.rfind("--", 0) == 0 && i + 1 < argc) a[k] = argv[++i];
    else list = k;
  }
  auto get = [&](const char *k, const char *d = "") { return a.count(k) ? a[k] : std::string(d); };
  auto cs = [&](const char *k) { return a.count(k) ? a[k].c_str() : nullptr; };
  const bool online = get("--mode", "online") == "online";
  const int threads = atoi(get("--threads", "2").c_str());
  const bool paced = a.count("--paced"), partials = a.count("--partials");
  const int chunk = 16 * atoi(get("--chunk-ms", "100").c_str());
  const int tail = 16 * atoi(get("--tail-ms", paced ? "1000" : "2000").c_str());
  std::string decoding = get("--decoding", "greedy_search");
  auto files = read_lines(list);

  double t0 = now_ms(), audio_s = 0;
  if (online) {
    SherpaOnnxOnlineRecognizerConfig c;
    memset(&c, 0, sizeof c);
    c.feat_config.sample_rate = 16000;
    c.feat_config.feature_dim = 80;
    c.model_config.transducer.encoder = cs("--encoder");
    c.model_config.transducer.decoder = cs("--decoder");
    c.model_config.transducer.joiner = cs("--joiner");
    c.model_config.nemo_ctc.model = cs("--nemo-ctc");
    c.model_config.zipformer2_ctc.model = cs("--zipformer2-ctc");
    c.model_config.tokens = cs("--tokens");
    c.model_config.model_type = cs("--model-type");
    c.model_config.num_threads = threads;
    c.model_config.provider = "cpu";
    c.decoding_method = decoding.c_str();
    c.max_active_paths = 4;
    const SherpaOnnxOnlineRecognizer *r = SherpaOnnxCreateOnlineRecognizer(&c);
    if (!r) { fprintf(stderr, "failed to create recognizer\n"); return 1; }
    mark_loaded();
    double load_ms = now_ms() - t0, wall0 = now_ms();
    for (auto &path : files) {
      std::vector<float> x;
      if (!read_wav(path, x)) { fprintf(stderr, "bad wav %s\n", path.c_str()); continue; }
      audio_s += x.size() / 16000.0;
      const size_t speech = x.size();
      // paced: the microphone keeps delivering (silent) audio after the speech while the app waits for an endpoint,
      // so the tail is released in real time too; unpaced: the tail is fed at once as part of the flush.
      if (paced) x.resize(speech + tail, 0.f);
      const SherpaOnnxOnlineStream *s = SherpaOnnxCreateOnlineStream(r);
      std::string last, hist;
      double first_partial = -1, stream_ms = 0, start = now_ms();
      for (size_t off = 0; off < x.size(); off += chunk) {
        size_t n = std::min((size_t)chunk, x.size() - off);
        if (paced) sleep_until_ms(start + (off + n) / 16.0);
        double c0 = now_ms();
        SherpaOnnxOnlineStreamAcceptWaveform(s, 16000, x.data() + off, n);
        while (SherpaOnnxIsOnlineStreamReady(r, s)) SherpaOnnxDecodeOnlineStream(r, s);
        const SherpaOnnxOnlineRecognizerResult *res = SherpaOnnxGetOnlineStreamResult(r, s);
        double c1 = now_ms();
        if (off < speech) stream_ms += c1 - c0;
        std::string t = res->text ? res->text : "";
        SherpaOnnxDestroyOnlineRecognizerResult(res);
        if (first_partial < 0 && t.find_first_not_of(' ') != std::string::npos)
          first_partial = paced ? c1 - start : (off + n) / 16.0 + (c1 - c0);
        if ((partials || paced) && t != last) {  // [audio ms, wall ms since audio start, text]
          char b[48];
          snprintf(b, sizeof b, "[%.0f,%.0f,\"", (off + n) / 16.0, paced ? c1 - start : 0.0);
          hist += (hist.empty() ? "" : ",") + std::string(b) + json_escape(t.c_str()) + "\"]";
        }
        last = t;
      }
      double f0 = now_ms();
      if (!paced) {
        std::vector<float> pad(tail, 0.f);
        SherpaOnnxOnlineStreamAcceptWaveform(s, 16000, pad.data(), pad.size());
      }
      SherpaOnnxOnlineStreamInputFinished(s);
      while (SherpaOnnxIsOnlineStreamReady(r, s)) SherpaOnnxDecodeOnlineStream(r, s);
      const SherpaOnnxOnlineRecognizerResult *res = SherpaOnnxGetOnlineStreamResult(r, s);
      double f1 = now_ms();
      // final_ms: from the end of the speech audio to the final text (paced: includes the real-time tail)
      double final_ms = paced ? f1 - (start + speech / 16.0) : f1 - f0;
      printf("{\"id\":\"%s\",\"dur\":%.3f,\"text\":\"%s\",\"decode_ms\":%.1f,\"flush_ms\":%.1f,\"final_ms\":%.1f,"
             "\"first_partial_ms\":%.1f,\"tail_ms\":%d%s%s%s}\n",
             base_id(path).c_str(), speech / 16000.0, json_escape(res->text).c_str(), stream_ms, f1 - f0, final_ms,
             first_partial, tail / 16, (partials || paced) ? ",\"partials\":[" : "", hist.c_str(),
             (partials || paced) ? "]" : "");
      fflush(stdout);
      SherpaOnnxDestroyOnlineRecognizerResult(res);
      SherpaOnnxDestroyOnlineStream(s);
    }
    print_rusage("online", load_ms, audio_s, now_ms() - wall0);
    SherpaOnnxDestroyOnlineRecognizer(r);
  } else {
    SherpaOnnxOfflineRecognizerConfig c;
    memset(&c, 0, sizeof c);
    c.feat_config.sample_rate = 16000;
    c.feat_config.feature_dim = 80;
    c.model_config.transducer.encoder = cs("--encoder");
    c.model_config.transducer.decoder = cs("--decoder");
    c.model_config.transducer.joiner = cs("--joiner");
    c.model_config.nemo_ctc.model = cs("--nemo-ctc");
    c.model_config.moonshine.preprocessor = cs("--moonshine-preprocessor");
    c.model_config.moonshine.encoder = cs("--moonshine-encoder");
    c.model_config.moonshine.uncached_decoder = cs("--moonshine-uncached");
    c.model_config.moonshine.cached_decoder = cs("--moonshine-cached");
    c.model_config.moonshine.merged_decoder = cs("--moonshine-merged");
    c.model_config.canary.encoder = cs("--canary-encoder");
    c.model_config.canary.decoder = cs("--canary-decoder");
    if (a.count("--canary-encoder")) {
      c.feat_config.feature_dim = 128;
      c.model_config.canary.src_lang = "en";
      c.model_config.canary.tgt_lang = "en";
      c.model_config.canary.use_pnc = 1;
    }
    c.model_config.tokens = cs("--tokens");
    c.model_config.model_type = cs("--model-type");
    c.model_config.num_threads = threads;
    c.model_config.provider = "cpu";
    c.decoding_method = decoding.c_str();
    c.max_active_paths = 4;
    const SherpaOnnxOfflineRecognizer *r = SherpaOnnxCreateOfflineRecognizer(&c);
    if (!r) { fprintf(stderr, "failed to create recognizer\n"); return 1; }
    mark_loaded();
    double load_ms = now_ms() - t0, wall0 = now_ms();
    for (auto &path : files) {
      std::vector<float> x;
      if (!read_wav(path, x)) { fprintf(stderr, "bad wav %s\n", path.c_str()); continue; }
      audio_s += x.size() / 16000.0;
      double c0 = now_ms();
      const SherpaOnnxOfflineStream *s = SherpaOnnxCreateOfflineStream(r);
      SherpaOnnxAcceptWaveformOffline(s, 16000, x.data(), x.size());
      SherpaOnnxDecodeOfflineStream(r, s);
      const SherpaOnnxOfflineRecognizerResult *res = SherpaOnnxGetOfflineStreamResult(s);
      double c1 = now_ms();
      printf("{\"id\":\"%s\",\"dur\":%.3f,\"text\":\"%s\",\"decode_ms\":%.1f,\"final_ms\":%.1f}\n", base_id(path).c_str(),
             x.size() / 16000.0, json_escape(res->text).c_str(), c1 - c0, c1 - c0);
      fflush(stdout);
      SherpaOnnxDestroyOfflineRecognizerResult(res);
      SherpaOnnxDestroyOfflineStream(s);
    }
    print_rusage("offline", load_ms, audio_s, now_ms() - wall0);
    SherpaOnnxDestroyOfflineRecognizer(r);
  }
  return 0;
}
