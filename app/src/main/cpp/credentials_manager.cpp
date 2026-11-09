#include "credentials_manager.h"
#include <sstream>
#include <iomanip>

namespace OpenChatSecurity {

static const std::string APP_SUBDOMAIN = "";
static const std::string APP_REGION = "";

std::string CredentialsManager::decodeHex(const char* hexStr) {
    std::string result;
    if (!hexStr) return result;
    size_t len = 0;
    while (hexStr[len] != '\0') len++;
    for (size_t i = 0; i + 1 < len; i += 2) {
        char byteChars[3] = { hexStr[i], hexStr[i + 1], '\0' };
        char byte = static_cast<char>(strtol(byteChars, nullptr, 16));
        result.push_back(byte);
    }
    return result;
}

std::string CredentialsManager::decodeBinary(const uint8_t* encodedBytes, size_t length, uint8_t xorKey) {
    std::string result;
    if (!encodedBytes || length == 0) return result;
    result.reserve(length);
    for (size_t i = 0; i < length; ++i) {
        result.push_back(static_cast<char>(encodedBytes[i] ^ xorKey));
    }
    return result;
}

std::string CredentialsManager::getSubdomain() {
    return APP_SUBDOMAIN;
}

std::string CredentialsManager::getRegion() {
    return APP_REGION;
}

} // namespace OpenChatSecurity

using namespace OpenChatSecurity;

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetSubdomain(JNIEnv* env, jclass /* clazz */) {
    return env->NewStringUTF(CredentialsManager::getSubdomain().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetRegion(JNIEnv* env, jclass /* clazz */) {
    return env->NewStringUTF(CredentialsManager::getRegion().c_str());
}

}
