#include <jni.h>

#include <dlfcn.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "onnxruntime_c_api.h"
#include "json.hpp"
#include "sentencepiece_processor.h"

namespace {

using nlohmann::json;
constexpr const char* kRequiredOrtVersion = "1.23.2";
constexpr int64_t kVocabularySize = 128112;
constexpr int64_t kBaseVocabularySize = 128004;
constexpr int64_t kHiddenSize = 1024;
constexpr int64_t kLayers = 12;
constexpr int64_t kHeads = 16;
constexpr int64_t kHeadSize = 64;
// FAIRSEQ_LANGUAGE_CODES["m2m100"] indices, added to the 128004-entry base vocabulary.
constexpr int64_t kEnglishLanguageTokenId = 128022;  // index 18
constexpr int64_t kSpanishLanguageTokenId = 128023;  // index 19
constexpr int64_t kMandarinLanguageTokenId = 128102; // index 98

bool isSupportedLanguageTokenId(int64_t tokenId) {
  return tokenId == kEnglishLanguageTokenId || tokenId == kSpanishLanguageTokenId ||
         tokenId == kMandarinLanguageTokenId;
}

void throwJava(JNIEnv* env, const char* className, const std::string& message) {
  if (env->ExceptionCheck()) return;
  jclass exceptionClass = env->FindClass(className);
  if (exceptionClass != nullptr) {
    env->ThrowNew(exceptionClass, message.c_str());
    env->DeleteLocalRef(exceptionClass);
  }
}

void throwState(JNIEnv* env, const std::string& message) {
  throwJava(env, "java/lang/IllegalStateException", message);
}

void checkOrt(const OrtApi* api, OrtStatus* status) {
  if (status == nullptr) return;
  const char* errorMessage = api->GetErrorMessage(status);
  const std::string message = errorMessage == nullptr ? "ONNX Runtime error" : errorMessage;
  api->ReleaseStatus(status);
  throw std::runtime_error(message);
}

struct OrtValues {
  explicit OrtValues(const OrtApi* runtimeApi) : api(runtimeApi) {}
  ~OrtValues() { clear(); }
  OrtValues(const OrtValues&) = delete;
  OrtValues& operator=(const OrtValues&) = delete;

  void clear() {
    if (api != nullptr) {
      for (OrtValue* value : values) {
        if (value != nullptr) api->ReleaseValue(value);
      }
    }
    values.clear();
  }

  const OrtApi* api;
  std::vector<OrtValue*> values;
};

class M2m100Model {
 public:
  explicit M2m100Model(const std::string& root) {
    runtimeLibrary_ = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
    if (runtimeLibrary_ == nullptr) {
      throw std::runtime_error(std::string("No se pudo abrir el ONNX Runtime de Moonshine: ") + dlerror());
    }

    using GetApiBaseFunction = const OrtApiBase* (*)();
    auto getApiBase = reinterpret_cast<GetApiBaseFunction>(dlsym(runtimeLibrary_, "OrtGetApiBase"));
    if (getApiBase == nullptr) {
      release();
      throw std::runtime_error("El runtime compartido no exporta OrtGetApiBase.");
    }
    apiBase_ = getApiBase();
    const char* runtimeVersion = apiBase_ == nullptr ? nullptr : apiBase_->GetVersionString();
    if (runtimeVersion == nullptr || std::string(runtimeVersion) != kRequiredOrtVersion) {
      const std::string actualVersion = runtimeVersion == nullptr ? "desconocida" : runtimeVersion;
      release();
      throw std::runtime_error(
          "Se requiere ONNX Runtime 1.23.2; versión cargada: " +
          actualVersion);
    }
    api_ = apiBase_->GetApi(ORT_API_VERSION);
    if (api_ == nullptr) {
      release();
      throw std::runtime_error("La API C de ONNX Runtime no coincide con los headers 1.23.2.");
    }

    try {
      loadTokenizer(root);
      checkOrt(api_, api_->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "voztranslate-m2m100", &environment_));

      OrtSessionOptions* options = nullptr;
      checkOrt(api_, api_->CreateSessionOptions(&options));
      try {
        checkOrt(api_, api_->SetIntraOpNumThreads(options, 2));
        checkOrt(api_, api_->SetInterOpNumThreads(options, 1));
        checkOrt(api_, api_->SetSessionExecutionMode(options, ORT_SEQUENTIAL));
        checkOrt(api_, api_->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL));
        const std::string encoderPath = root + "/onnx/encoder_model_quantized.onnx";
        const std::string decoderPath = root + "/onnx/decoder_model_merged_quantized.onnx";
        checkOrt(api_, api_->CreateSession(environment_, encoderPath.c_str(), options, &encoder_));
        checkOrt(api_, api_->CreateSession(environment_, decoderPath.c_str(), options, &decoder_));
      } catch (...) {
        api_->ReleaseSessionOptions(options);
        throw;
      }
      api_->ReleaseSessionOptions(options);
      checkOrt(api_, api_->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memoryInfo_));
    } catch (...) {
      release();
      throw;
    }
  }

  ~M2m100Model() { release(); }

  std::string translate(
      JNIEnv* env,
      const std::string& text,
      int32_t sourceLanguageTokenId,
      int32_t targetLanguageTokenId,
      int32_t decoderStartTokenId,
      int32_t forcedFirstTokenId,
      int32_t eosTokenId,
      int32_t maxLength,
      jobject cancellation) {
    if (text.empty()) throw std::invalid_argument("El texto de entrada para M2M100 está vacío.");
    if (!isSupportedLanguageTokenId(sourceLanguageTokenId) ||
        !isSupportedLanguageTokenId(targetLanguageTokenId)) {
      throw std::invalid_argument("El ID de idioma M2M100 no es inglés, español o mandarín según la tabla oficial.");
    }
    if (decoderStartTokenId != 2 || eosTokenId != 2 || forcedFirstTokenId != targetLanguageTokenId) {
      throw std::invalid_argument("Los tokens iniciales no coinciden con la semántica M2M100 fijada.");
    }
    if (maxLength < 2 || maxLength > 1024) {
      throw std::invalid_argument("El límite de generación M2M100 debe estar entre 2 y 1024 tokens.");
    }
    checkCancelled(env, cancellation);

    std::vector<std::string> pieces;
    const auto spStatus = tokenizer_.Encode(text, &pieces);
    if (!spStatus.ok()) throw std::runtime_error("SentencePiece no pudo tokenizar el texto: " + spStatus.ToString());
    if (pieces.size() + 2 > 1024) {
      throw std::invalid_argument("El texto supera los 1024 tokens admitidos por el encoder M2M100.");
    }

    std::vector<int64_t> encoderIds;
    encoderIds.reserve(pieces.size() + 2);
    encoderIds.push_back(sourceLanguageTokenId);
    for (const std::string& piece : pieces) {
      const auto found = pieceToId_.find(piece);
      // M2M100Tokenizer._convert_token_to_id uses encoder.get(piece, encoder["<unk>"]).
      encoderIds.push_back(found == pieceToId_.end() ? 3 : found->second);
    }
    encoderIds.push_back(eosTokenId);
    std::vector<int64_t> attentionMask(encoderIds.size(), 1);
    const std::array<int64_t, 2> encoderShape{1, static_cast<int64_t>(encoderIds.size())};

    OrtValues encoderInputs(api_);
    encoderInputs.values.resize(2, nullptr);
    checkOrt(api_, api_->CreateTensorWithDataAsOrtValue(
        memoryInfo_, encoderIds.data(), encoderIds.size() * sizeof(int64_t), encoderShape.data(),
        encoderShape.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &encoderInputs.values[0]));
    checkOrt(api_, api_->CreateTensorWithDataAsOrtValue(
        memoryInfo_, attentionMask.data(), attentionMask.size() * sizeof(int64_t), encoderShape.data(),
        encoderShape.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &encoderInputs.values[1]));

    const std::array<const char*, 2> encoderInputNames{"input_ids", "attention_mask"};
    const std::array<const char*, 1> encoderOutputNames{"last_hidden_state"};
    OrtValues encoderOutputs(api_);
    encoderOutputs.values.resize(1, nullptr);
    checkCancelled(env, cancellation);
    checkOrt(api_, api_->Run(
        encoder_, nullptr, encoderInputNames.data(), encoderInputs.values.data(), encoderInputs.values.size(),
        encoderOutputNames.data(), encoderOutputNames.size(), encoderOutputs.values.data()));
    validateFloatTensor(encoderOutputs.values[0], {1, static_cast<int64_t>(encoderIds.size()), kHiddenSize});
    checkCancelled(env, cancellation);

    std::vector<std::string> decoderInputNames{
        "encoder_attention_mask", "input_ids", "encoder_hidden_states"};
    std::vector<std::string> decoderOutputNames{"logits"};
    for (int64_t layer = 0; layer < kLayers; ++layer) {
      for (const char* block : {"decoder", "encoder"}) {
        for (const char* kind : {"key", "value"}) {
          decoderInputNames.push_back(
              "past_key_values." + std::to_string(layer) + "." + block + "." + kind);
          decoderOutputNames.push_back(
              "present." + std::to_string(layer) + "." + block + "." + kind);
        }
      }
    }
    decoderInputNames.push_back("use_cache_branch");
    std::vector<const char*> decoderInputNamePointers;
    std::vector<const char*> decoderOutputNamePointers;
    decoderInputNamePointers.reserve(decoderInputNames.size());
    decoderOutputNamePointers.reserve(decoderOutputNames.size());
    for (const auto& name : decoderInputNames) decoderInputNamePointers.push_back(name.c_str());
    for (const auto& name : decoderOutputNames) decoderOutputNamePointers.push_back(name.c_str());

    OrtValues initialCaches(api_);
    initialCaches.values.resize(kLayers * 4, nullptr);
    const float emptyCacheData = 0.0F;
    const std::array<int64_t, 4> emptyCacheShape{1, kHeads, 0, kHeadSize};
    for (OrtValue*& cache : initialCaches.values) {
      checkOrt(api_, api_->CreateTensorWithDataAsOrtValue(
          memoryInfo_, const_cast<float*>(&emptyCacheData), 0, emptyCacheShape.data(), emptyCacheShape.size(),
          ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &cache));
    }

    int64_t decoderStart = decoderStartTokenId;
    const std::array<int64_t, 2> decoderStartShape{1, 1};
    OrtValues decoderStartInput(api_);
    decoderStartInput.values.resize(1, nullptr);
    checkOrt(api_, api_->CreateTensorWithDataAsOrtValue(
        memoryInfo_, &decoderStart, sizeof(decoderStart), decoderStartShape.data(), decoderStartShape.size(),
        ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &decoderStartInput.values[0]));
    bool useCache = false;
    OrtValues cacheBranchInput(api_);
    cacheBranchInput.values.resize(1, nullptr);
    const std::array<int64_t, 1> cacheBranchShape{1};
    checkOrt(api_, api_->CreateTensorWithDataAsOrtValue(
        memoryInfo_, &useCache, sizeof(useCache), cacheBranchShape.data(), cacheBranchShape.size(),
        ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL, &cacheBranchInput.values[0]));

    std::vector<OrtValue*> firstDecoderInputs;
    firstDecoderInputs.reserve(decoderInputNames.size());
    firstDecoderInputs.push_back(encoderInputs.values[1]);
    firstDecoderInputs.push_back(decoderStartInput.values[0]);
    firstDecoderInputs.push_back(encoderOutputs.values[0]);
    firstDecoderInputs.insert(firstDecoderInputs.end(), initialCaches.values.begin(), initialCaches.values.end());
    firstDecoderInputs.push_back(cacheBranchInput.values[0]);
    OrtValues decoderOutputs(api_);
    decoderOutputs.values.resize(decoderOutputNames.size(), nullptr);
    checkOrt(api_, api_->Run(
        decoder_, nullptr, decoderInputNamePointers.data(), firstDecoderInputs.data(), firstDecoderInputs.size(),
        decoderOutputNamePointers.data(), decoderOutputNamePointers.size(), decoderOutputs.values.data()));
    validateLogits(decoderOutputs.values[0]);
    if (decoderOutputs.values.size() != 1 + kLayers * 4) {
      throw std::runtime_error("El decoder M2M100 devolvió un conjunto de cachés inesperado.");
    }
    for (int64_t layer = 0; layer < kLayers; ++layer) {
      const size_t base = 1 + static_cast<size_t>(layer * 4);
      validateFloatTensor(decoderOutputs.values[base], {1, kHeads, 1, kHeadSize});
      validateFloatTensor(decoderOutputs.values[base + 1], {1, kHeads, 1, kHeadSize});
      validateFloatTensor(decoderOutputs.values[base + 2],
                          {1, kHeads, static_cast<int64_t>(encoderIds.size()), kHeadSize});
      validateFloatTensor(decoderOutputs.values[base + 3],
                          {1, kHeads, static_cast<int64_t>(encoderIds.size()), kHeadSize});
    }
    // The pinned merged graph returns invalid encoder-cache shapes from its cached branch.
    // Keep the valid cross-attention caches from the initial prefill for all later tokens.
    OrtValues prefillOutputs(api_);
    prefillOutputs.values.swap(decoderOutputs.values);
    initialCaches.clear();
    decoderStartInput.clear();
    cacheBranchInput.clear();
    useCache = true;
    cacheBranchInput.values.resize(1, nullptr);
    checkOrt(api_, api_->CreateTensorWithDataAsOrtValue(
        memoryInfo_, &useCache, sizeof(useCache), cacheBranchShape.data(), cacheBranchShape.size(),
        ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL, &cacheBranchInput.values[0]));

    std::vector<int64_t> generated;
    generated.reserve(static_cast<size_t>(maxLength - 1));
    generated.push_back(forcedFirstTokenId);
    int64_t currentToken = forcedFirstTokenId;
    std::vector<OrtValue*> cacheInputs;
    cacheInputs.reserve(kLayers * 4);
    for (int64_t layer = 0; layer < kLayers; ++layer) {
      const size_t base = 1 + static_cast<size_t>(layer * 4);
      cacheInputs.push_back(prefillOutputs.values[base]);
      cacheInputs.push_back(prefillOutputs.values[base + 1]);
      cacheInputs.push_back(prefillOutputs.values[base + 2]);
      cacheInputs.push_back(prefillOutputs.values[base + 3]);
    }
    int64_t decoderCacheLength = 1;

    while (generated.size() < static_cast<size_t>(maxLength - 1)) {
      checkCancelled(env, cancellation);
      int64_t tokenInput = currentToken;
      OrtValues tokenValue(api_);
      tokenValue.values.resize(1, nullptr);
      checkOrt(api_, api_->CreateTensorWithDataAsOrtValue(
          memoryInfo_, &tokenInput, sizeof(tokenInput), decoderStartShape.data(), decoderStartShape.size(),
          ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &tokenValue.values[0]));
      useCache = true;
      std::vector<OrtValue*> stepInputs;
      stepInputs.reserve(decoderInputNames.size());
      stepInputs.push_back(encoderInputs.values[1]);
      stepInputs.push_back(tokenValue.values[0]);
      stepInputs.push_back(encoderOutputs.values[0]);
      stepInputs.insert(stepInputs.end(), cacheInputs.begin(), cacheInputs.end());
      stepInputs.push_back(cacheBranchInput.values[0]);

      OrtValues nextOutputs(api_);
      nextOutputs.values.resize(decoderOutputNames.size(), nullptr);
      checkOrt(api_, api_->Run(
          decoder_, nullptr, decoderInputNamePointers.data(), stepInputs.data(), stepInputs.size(),
          decoderOutputNamePointers.data(), decoderOutputNamePointers.size(), nextOutputs.values.data()));
      validateLogits(nextOutputs.values[0]);
      ++decoderCacheLength;
      for (int64_t layer = 0; layer < kLayers; ++layer) {
        const size_t base = 1 + static_cast<size_t>(layer * 4);
        validateFloatTensor(nextOutputs.values[base], {1, kHeads, decoderCacheLength, kHeadSize});
        validateFloatTensor(nextOutputs.values[base + 1], {1, kHeads, decoderCacheLength, kHeadSize});
      }
      const int64_t nextToken = greedyToken(nextOutputs.values[0]);
      decoderOutputs.clear();
      decoderOutputs.values.swap(nextOutputs.values);
      cacheInputs.clear();
      for (int64_t layer = 0; layer < kLayers; ++layer) {
        const size_t base = 1 + static_cast<size_t>(layer * 4);
        cacheInputs.push_back(decoderOutputs.values[base]);
        cacheInputs.push_back(decoderOutputs.values[base + 1]);
        cacheInputs.push_back(prefillOutputs.values[base + 2]);
        cacheInputs.push_back(prefillOutputs.values[base + 3]);
      }
      if (nextToken == eosTokenId) break;
      generated.push_back(nextToken);
      currentToken = nextToken;
    }

    std::vector<std::string> outputPieces;
    for (int64_t token : generated) {
      if (token < 4 || token >= kBaseVocabularySize) continue;
      const std::string& piece = idToPiece_.at(static_cast<size_t>(token));
      if (!piece.empty()) outputPieces.push_back(piece);
    }
    std::string result;
    const auto decodeStatus = tokenizer_.Decode(outputPieces, &result);
    if (!decodeStatus.ok()) throw std::runtime_error("SentencePiece no pudo decodificar: " + decodeStatus.ToString());
    return result;
  }

 private:
  void loadTokenizer(const std::string& root) {
    const std::string modelPath = root + "/sentencepiece.bpe.model";
    const auto status = tokenizer_.Load(modelPath);
    if (!status.ok()) throw std::runtime_error("No se pudo cargar SentencePiece: " + status.ToString());
    if (tokenizer_.GetPieceSize() != 128000) {
      throw std::runtime_error("El tamaño SentencePiece no coincide con el tokenizer M2M100 fijado.");
    }
    std::ifstream vocabStream(root + "/vocab.json", std::ios::binary);
    if (!vocabStream) throw std::runtime_error("No se pudo abrir vocab.json de M2M100.");
    const json vocabulary = json::parse(vocabStream);
    if (!vocabulary.is_object() || vocabulary.size() != kBaseVocabularySize) {
      throw std::runtime_error("vocab.json no contiene las 128004 entradas esperadas.");
    }
    idToPiece_.resize(kBaseVocabularySize);
    std::vector<bool> assigned(kBaseVocabularySize, false);
    for (auto iterator = vocabulary.begin(); iterator != vocabulary.end(); ++iterator) {
      if (!iterator.value().is_number_integer()) throw std::runtime_error("vocab.json tiene un ID no entero.");
      const int64_t id = iterator.value().get<int64_t>();
      if (id < 0 || id >= kBaseVocabularySize || assigned[static_cast<size_t>(id)]) {
        throw std::runtime_error("vocab.json tiene IDs repetidos o fuera de rango.");
      }
      const std::string piece = iterator.key();
      idToPiece_[static_cast<size_t>(id)] = piece;
      pieceToId_.emplace(piece, id);
      assigned[static_cast<size_t>(id)] = true;
    }
    if (std::find(assigned.begin(), assigned.end(), false) != assigned.end() ||
        idToPiece_[0] != "<s>" || idToPiece_[1] != "<pad>" || idToPiece_[2] != "</s>" ||
        idToPiece_[3] != "<unk>") {
      throw std::runtime_error("vocab.json no coincide con la tabla especial M2M100 fijada.");
    }
  }

  void validateFloatTensor(OrtValue* tensor, const std::vector<int64_t>& expectedShape) const {
    OrtTensorTypeAndShapeInfo* info = nullptr;
    checkOrt(api_, api_->GetTensorTypeAndShape(tensor, &info));
    ONNXTensorElementDataType elementType;
    size_t rank;
    checkOrt(api_, api_->GetTensorElementType(info, &elementType));
    checkOrt(api_, api_->GetDimensionsCount(info, &rank));
    std::vector<int64_t> dimensions(rank);
    checkOrt(api_, api_->GetDimensions(info, dimensions.data(), dimensions.size()));
    api_->ReleaseTensorTypeAndShapeInfo(info);
    if (elementType != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || dimensions != expectedShape) {
      throw std::runtime_error("La salida float del modelo M2M100 tiene forma/tipo inesperados.");
    }
  }

  void validateLogits(OrtValue* logits) const {
    OrtTensorTypeAndShapeInfo* info = nullptr;
    checkOrt(api_, api_->GetTensorTypeAndShape(logits, &info));
    ONNXTensorElementDataType elementType;
    size_t rank;
    checkOrt(api_, api_->GetTensorElementType(info, &elementType));
    checkOrt(api_, api_->GetDimensionsCount(info, &rank));
    std::vector<int64_t> dimensions(rank);
    checkOrt(api_, api_->GetDimensions(info, dimensions.data(), dimensions.size()));
    api_->ReleaseTensorTypeAndShapeInfo(info);
    if (elementType != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || dimensions.size() != 3 ||
        dimensions[0] != 1 || dimensions[1] != 1 || dimensions[2] != kVocabularySize) {
      throw std::runtime_error("Los logits del decoder M2M100 tienen forma/tipo inesperados.");
    }
  }

  int64_t greedyToken(OrtValue* logits) const {
    float* values = nullptr;
    checkOrt(api_, api_->GetTensorMutableData(logits, reinterpret_cast<void**>(&values)));
    if (values == nullptr) throw std::runtime_error("El decoder M2M100 devolvió logits vacíos.");
    const auto maximum = std::max_element(values, values + kVocabularySize);
    return static_cast<int64_t>(std::distance(values, maximum));
  }

  void release() noexcept {
    if (api_ != nullptr) {
      if (decoder_ != nullptr) api_->ReleaseSession(decoder_);
      if (encoder_ != nullptr) api_->ReleaseSession(encoder_);
      if (memoryInfo_ != nullptr) api_->ReleaseMemoryInfo(memoryInfo_);
      if (environment_ != nullptr) api_->ReleaseEnv(environment_);
    }
    memoryInfo_ = nullptr;
    encoder_ = nullptr;
    decoder_ = nullptr;
    environment_ = nullptr;
    api_ = nullptr;
    apiBase_ = nullptr;
    if (runtimeLibrary_ != nullptr) dlclose(runtimeLibrary_);
    runtimeLibrary_ = nullptr;
  }

  static void checkCancelled(JNIEnv* env, jobject cancellation) {
    if (cancellation == nullptr) return;
    jclass atomicBooleanClass = env->GetObjectClass(cancellation);
    if (atomicBooleanClass == nullptr) return;
    jmethodID getMethod = env->GetMethodID(atomicBooleanClass, "get", "()Z");
    if (getMethod == nullptr) {
      env->DeleteLocalRef(atomicBooleanClass);
      return;
    }
    const jboolean isCancelled = env->CallBooleanMethod(cancellation, getMethod);
    env->DeleteLocalRef(atomicBooleanClass);
    if (env->ExceptionCheck()) return;
    if (isCancelled == JNI_TRUE) {
      throwJava(env, "java/util/concurrent/CancellationException", "La inferencia M2M100 fue cancelada.");
      throw std::runtime_error("M2M100 inference cancelled");
    }
  }

  void* runtimeLibrary_ = nullptr;
  const OrtApiBase* apiBase_ = nullptr;
  const OrtApi* api_ = nullptr;
  OrtEnv* environment_ = nullptr;
  OrtSession* encoder_ = nullptr;
  OrtSession* decoder_ = nullptr;
  OrtMemoryInfo* memoryInfo_ = nullptr;
  sentencepiece::SentencePieceProcessor tokenizer_;
  std::unordered_map<std::string, int64_t> pieceToId_;
  std::vector<std::string> idToPiece_;
};

void appendUtf8(std::string& output, uint32_t codePoint) {
  if (codePoint <= 0x7f) {
    output.push_back(static_cast<char>(codePoint));
  } else if (codePoint <= 0x7ff) {
    output.push_back(static_cast<char>(0xc0 | (codePoint >> 6)));
    output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
  } else if (codePoint <= 0xffff) {
    output.push_back(static_cast<char>(0xe0 | (codePoint >> 12)));
    output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3f)));
    output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
  } else {
    output.push_back(static_cast<char>(0xf0 | (codePoint >> 18)));
    output.push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3f)));
    output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3f)));
    output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
  }
}

std::string fromJavaString(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const jsize length = env->GetStringLength(value);
  const jchar* chars = env->GetStringChars(value, nullptr);
  if (chars == nullptr) throw std::runtime_error("No se pudo leer un texto JNI.");
  std::string result;
  result.reserve(static_cast<size_t>(length) * 3);
  for (jsize i = 0; i < length; ++i) {
    uint32_t codePoint = chars[i];
    if (codePoint >= 0xd800 && codePoint <= 0xdbff && i + 1 < length &&
        chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
      codePoint = 0x10000 + ((codePoint - 0xd800) << 10) + (chars[++i] - 0xdc00);
    } else if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
      codePoint = 0xfffd;
    }
    appendUtf8(result, codePoint);
  }
  env->ReleaseStringChars(value, chars);
  return result;
}

jstring toJavaString(JNIEnv* env, const std::string& value) {
  std::vector<jchar> utf16;
  utf16.reserve(value.size());
  size_t i = 0;
  while (i < value.size()) {
    const uint8_t first = static_cast<uint8_t>(value[i++]);
    uint32_t codePoint = 0xfffd;
    size_t continuationCount = 0;
    if (first <= 0x7f) {
      codePoint = first;
    } else if ((first & 0xe0) == 0xc0) {
      codePoint = first & 0x1f;
      continuationCount = 1;
    } else if ((first & 0xf0) == 0xe0) {
      codePoint = first & 0x0f;
      continuationCount = 2;
    } else if ((first & 0xf8) == 0xf0) {
      codePoint = first & 0x07;
      continuationCount = 3;
    }
    bool valid = i + continuationCount <= value.size();
    for (size_t j = 0; valid && j < continuationCount; ++j) {
      const uint8_t continuation = static_cast<uint8_t>(value[i++]);
      valid = (continuation & 0xc0) == 0x80;
      codePoint = (codePoint << 6) | (continuation & 0x3f);
    }
    if (!valid || codePoint > 0x10ffff || (codePoint >= 0xd800 && codePoint <= 0xdfff) ||
        (continuationCount == 1 && codePoint < 0x80) ||
        (continuationCount == 2 && codePoint < 0x800) ||
        (continuationCount == 3 && codePoint < 0x10000)) {
      codePoint = 0xfffd;
    }
    if (codePoint <= 0xffff) {
      utf16.push_back(static_cast<jchar>(codePoint));
    } else {
      codePoint -= 0x10000;
      utf16.push_back(static_cast<jchar>(0xd800 + (codePoint >> 10)));
      utf16.push_back(static_cast<jchar>(0xdc00 + (codePoint & 0x3ff)));
    }
  }
  return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_voztranslate_app_ai_M2m100JniInferencePort_nativeCreate(JNIEnv* env, jclass, jstring modelRoot) {
  try {
    auto model = std::make_unique<M2m100Model>(fromJavaString(env, modelRoot));
    return reinterpret_cast<jlong>(model.release());
  } catch (const std::exception& error) {
    throwState(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_voztranslate_app_ai_M2m100JniInferencePort_nativeTranslate(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring text,
    jint sourceLanguageTokenId,
    jint targetLanguageTokenId,
    jint decoderStartTokenId,
    jint forcedFirstGeneratedTokenId,
    jint eosTokenId,
    jint maxLength,
    jobject cancellation) {
  try {
    auto* model = reinterpret_cast<M2m100Model*>(handle);
    if (model == nullptr) throw std::runtime_error("El modelo nativo M2M100 no está cargado.");
    const std::string result = model->translate(
        env, fromJavaString(env, text), sourceLanguageTokenId, targetLanguageTokenId,
        decoderStartTokenId, forcedFirstGeneratedTokenId, eosTokenId, maxLength, cancellation);
    if (env->ExceptionCheck()) return nullptr;
    return toJavaString(env, result);
  } catch (const std::exception& error) {
    if (!env->ExceptionCheck()) throwState(env, error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_voztranslate_app_ai_M2m100JniInferencePort_nativeDestroy(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<M2m100Model*>(handle);
}
