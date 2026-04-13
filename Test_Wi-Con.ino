#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <WiFi.h>
#include <esp_wifi.h>

#define SERVICE_UUID         "12345678-1234-1234-1234-1234567890ab"
#define CHARACTERISTIC_UUID  "abcdefab-1234-5678-9abc-abcdef123456"
#define COMMAND_UUID         "cdef1234-5678-90ab-cdef-1234567890ab"

BLEServer* pServer = nullptr;
BLECharacteristic* pCharacteristic = nullptr;
BLECharacteristic* pCommandCharacteristic = nullptr;

bool deviceConnected = false;
unsigned long lastScanTime = 0;
const unsigned long scanInterval = 2000;

String selectedSSID = "";
bool inOptimizationMode = false;
unsigned long optimizationStartTime = 0;
std::vector<int> optimizeRSSIs;

int optimizationIndex = 0;
int totalNetworks = 0;

// Store last scan results
std::vector<String> scannedSSIDs;
std::vector<int> scannedRSSIs;

void sendChunked(String data) {
  String wrapped = "<START>" + data + "<END>";
  int chunkSize = 20;
  for (int i = 0; i < wrapped.length(); i += chunkSize) {
    String chunk = wrapped.substring(i, min(i + chunkSize, (int)wrapped.length()));
    pCharacteristic->setValue(chunk.c_str());
    pCharacteristic->notify();
    Serial.println("BLE Chunk Sent: " + chunk);
    delay(20);
  }
}

void performWiFiScan() {
  int n = WiFi.scanNetworks();
  String wifiData = "[";
  int limit = min(n, 30);

  for (int i = 0; i < limit; ++i) {
    String securityType;
    uint8_t encryptionType = WiFi.encryptionType(i);
    switch (encryptionType) {
      case WIFI_AUTH_OPEN: securityType = "Open"; break;
      case WIFI_AUTH_WEP: securityType = "WEP"; break;
      case WIFI_AUTH_WPA_PSK: securityType = "WPA"; break;
      case WIFI_AUTH_WPA2_PSK: securityType = "WPA2"; break;
      case WIFI_AUTH_WPA_WPA2_PSK: securityType = "WPA/WPA2"; break;
      case WIFI_AUTH_WPA2_ENTERPRISE: securityType = "WPA2-Enterprise"; break;
      default: securityType = "Unknown"; break;
    }

    String ssid = WiFi.SSID(i);
    int rssi = WiFi.RSSI(i);
    String mac = WiFi.BSSIDstr(i);  

    wifiData += "{\"ssid\":\"" + ssid +
                "\",\"rssi\":" + String(rssi) +
                ",\"security\":\"" + securityType +
                "\",\"mac\":\"" + mac + 
                "\",\"channel\":" + String(WiFi.channel(i)) + "}";  

    if (i != limit - 1) wifiData += ",";
  }

  wifiData += "]";
  Serial.println("Full Scan JSON: " + wifiData);
  sendChunked(wifiData);
  Serial.println("Sent full scan data");
}


void startOptimizationScan() {
  // Clear previous
  scannedSSIDs.clear();
  scannedRSSIs.clear();

  totalNetworks = WiFi.scanNetworks();
  Serial.println("Optimization scan started...");
  for (int i = 0; i < totalNetworks; ++i) {
    String ssid = WiFi.SSID(i);
    int rssi = WiFi.RSSI(i);
    scannedSSIDs.push_back(ssid);
    scannedRSSIs.push_back(rssi);
    Serial.println("Found SSID: [" + ssid + "], RSSI: " + String(rssi));
  }
}

void handleOptimization() {
  int n = WiFi.scanNetworks();
  bool found = false;

  Serial.println("Scanning for optimization SSID: " + selectedSSID);
  for (int i = 0; i < n; i++) {
    String ssid = WiFi.SSID(i);
    int rssi = WiFi.RSSI(i);

    Serial.println("→ Found: " + ssid + " | RSSI: " + String(rssi));

    if (ssid.equalsIgnoreCase(selectedSSID)) {
      optimizeRSSIs.push_back(rssi);
      Serial.println("✅ Matched SSID: " + ssid + ", RSSI: " + String(rssi));
      found = true;
      break;
    }
  }

  if (!found) {
    Serial.println("❌ Selected SSID not found in this scan");
  }
}


class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println("Central connected");
  }

  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    Serial.println("Central disconnected");
    BLEDevice::startAdvertising();
  }
};

class CommandCallback : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pChar) override {
    std::string value = std::string(pChar->getValue().c_str());
    String command = String(value.c_str());

    if (command == "refresh") {
      Serial.println("Manual refresh triggered!");
      performWiFiScan();
    } else if (command.startsWith("optimize:")) {
      selectedSSID = command.substring(9);
      inOptimizationMode = true;
      optimizationStartTime = millis();
      optimizeRSSIs.clear();
      optimizationIndex = 0;
      startOptimizationScan();
      Serial.println("Entered optimization mode for SSID: " + selectedSSID);
    }
  }
};

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();

  BLEDevice::init("ESP32-WiFi-Scanner");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);

  pCharacteristic = pService->createCharacteristic(
    CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  pCharacteristic->addDescriptor(new BLE2902());

  pCommandCharacteristic = pService->createCharacteristic(
    COMMAND_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );
  pCommandCharacteristic->setCallbacks(new CommandCallback());

  pService->start();
  BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->start();

  Serial.println("BLE Advertising started...");
}

void loop() {
  if (!deviceConnected) return;

  if (inOptimizationMode) {
    if (millis() - optimizationStartTime < 5000) {
      handleOptimization();
      delay(500);
    } else {
      inOptimizationMode = false;

      int total = 0;
      for (int rssi : optimizeRSSIs) total += rssi;
      int avg = optimizeRSSIs.empty() ? -100 : total / optimizeRSSIs.size();

      String result = "{\"ssid\":\"" + selectedSSID + "\",\"avg_rssi\":" + String(avg) + "}";
      sendChunked(result);
      Serial.println("Optimization Result JSON: " + result);
      Serial.println("Exiting optimization mode. Sent avg: " + String(avg));
      selectedSSID = "";
    }
  } else {
    if (millis() - lastScanTime > scanInterval) {
      lastScanTime = millis();
      performWiFiScan();
    }
  }
}
