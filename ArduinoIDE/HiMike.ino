#include <driver/i2s.h>

// ====== PIN assignment (Freenove ESP32-S3-WROOM Board Lite) ======
#define I2S_SCK  36   // BCLK (SCK)
#define I2S_WS   37   // LRCLK (WS)
#define I2S_SD   38   // DOUT  (Mic -> ESP32)

// ====== Audio parameters ======
static const int SAMPLE_RATE    = 16000;         // 16 kHz
static const int DMA_BUF_LEN    = 512;           // 32-bit samples per DMA buffer
static const int DMA_BUF_COUNT  = 6;
static const int FRAME_SAMPLES  = 256;           // chunk to convert/send (16-bit samples)

// High-pass (DCカット) 一次IIR: y[n] = a*(y[n-1] + x[n] - x[n-1])
// 16kHzで 20Hz 付近のカットに相当する係数目安
static const float HPF_A = 0.995f;

void setup() {
  // USB-CDC serial
  Serial.begin(115200);
  delay(500);              // USBが立ち上がる時間を少し待つ
  Serial.println("Hello");
  unsigned long start = millis();

  // CDC接続確認ループ
  bool connected = false;
  while (millis() - start < 3000) { // 最大3秒待機
    if (Serial) {
      connected = true;
      break;
    }
    delay(10);
  }

  if (connected) {
    Serial.println("✅ USB-CDC Connected!");
  } else {
    Serial.println("❌fuck");
  }
  // ---- I2S config ----
  i2s_config_t i2s_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,  // INMP441は24bit左詰め→32bit枠で受信
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,   // L/Rピン=GNDで左スロット
    .communication_format = I2S_COMM_FORMAT_I2S,
    .intr_alloc_flags = 0,
    .dma_buf_count = DMA_BUF_COUNT,
    .dma_buf_len = DMA_BUF_LEN,
    .use_apll = false
  };

  i2s_pin_config_t pin_config = {
    .bck_io_num = I2S_SCK,
    .ws_io_num = I2S_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,  // RXのみ
    .data_in_num = I2S_SD
  };

  // Install & set pins
  i2s_driver_install(I2S_NUM_0, &i2s_config, 0, NULL);
  i2s_set_pin(I2S_NUM_0, &pin_config);
  // 明示クロック
  i2s_set_clk(I2S_NUM_0, SAMPLE_RATE, I2S_BITS_PER_SAMPLE_32BIT, I2S_CHANNEL_MONO);

  unsigned long t0 = millis();
  while (!Serial && millis() - t0 < 3000) { delay(10); }
  Serial.println("USB-CDC connected (I2S app)");
  if (connected) Serial.println("🎤 I2S Ready!");

}

// 32bit → 16bit 変換＋HPF（DC/超低域抑え）
// INMP441は24bit左詰めなので右シフト量でゲイン調整（14が無難）
inline int16_t convert32to16_hpf(int32_t s32) {
  static int32_t x1 = 0;    // x[n-1]
  static float   y1 = 0.0f; // y[n-1]

  // 24bit左詰め → 16bit域へ
  s32 >>= 14;  // 小さければ13、大きければ15に調整

  // HPF
  int32_t x0 = s32;
  float y0 = HPF_A * (y1 + (float)x0 - (float)x1);

  // 次回用更新
  x1 = x0;
  y1 = y0;

  // クリップ＆16bitへ
  if (y0 > 32767.0f)  y0 = 32767.0f;
  if (y0 < -32768.0f) y0 = -32768.0f;
  return (int16_t)(y0);
}

void loop() {
  static int32_t rx32[DMA_BUF_LEN];   // 32-bit samples from I2S
  static int16_t tx16[FRAME_SAMPLES]; // 16-bit samples to send

  size_t bytes_read = 0;
  // ブロッキングでI2S受信
  i2s_read(I2S_NUM_0, (void*)rx32, sizeof(rx32), &bytes_read, portMAX_DELAY);
  while (Serial.available()) {
  Serial.write(Serial.read()); // エコー返し
  }

  int total = bytes_read / sizeof(int32_t);
  int i = 0;
  while (i < total) {
    int chunk = min(FRAME_SAMPLES, total - i);
    for (int k = 0; k < chunk; ++k) {
      tx16[k] = convert32to16_hpf(rx32[i + k]);
    }
    // 16-bit little endian RAW PCM をUSB CDCへ
    Serial.write((uint8_t*)tx16, chunk * sizeof(int16_t));
    i += chunk;
  }
}
