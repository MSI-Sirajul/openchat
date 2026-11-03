#include "credentials_manager.h"
#include <sstream>
#include <iomanip>

namespace OpenChatSecurity {

static const std::string NHOST_SUBDOMAIN = "stvvoprjibbedgcdlbib";
static const std::string NHOST_REGION = "ap-south-1";
static const std::string NHOST_STORAGE_URL = "https://stvvoprjibbedgcdlbib.storage.ap-south-1.nhost.run/v1";
static const std::string NHOST_GRAPHQL_URL = "https://stvvoprjibbedgcdlbib.graphql.ap-south-1.nhost.run/v1";
static const std::string NHOST_GRAPHQL_WS_URL = "wss://stvvoprjibbedgcdlbib.graphql.ap-south-1.nhost.run/v1";
static const std::string NHOST_AUTH_URL = "https://stvvoprjibbedgcdlbib.auth.ap-south-1.nhost.run/v1";
static const std::string NHOST_FUNCTIONS_URL = "https://stvvoprjibbedgcdlbib.functions.ap-south-1.nhost.run/v1";
static const std::string NHOST_HASURA_URL = "https://stvvoprjibbedgcdlbib.hasura.ap-south-1.nhost.run/console";

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
    return NHOST_SUBDOMAIN;
}

std::string CredentialsManager::getRegion() {
    return NHOST_REGION;
}

std::string CredentialsManager::getAuthUrl() {
    return NHOST_AUTH_URL;
}

std::string CredentialsManager::getGraphqlUrl() {
    return NHOST_GRAPHQL_URL;
}

std::string CredentialsManager::getGraphqlWsUrl() {
    return NHOST_GRAPHQL_WS_URL;
}

std::string CredentialsManager::getStorageUrl() {
    return NHOST_STORAGE_URL;
}

std::string CredentialsManager::getFunctionsUrl() {
    return NHOST_FUNCTIONS_URL;
}

std::string CredentialsManager::getHasuraUrl() {
    return NHOST_HASURA_URL;
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

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetAuthUrl(JNIEnv* env, jclass /* clazz */) {
    return env->NewStringUTF(CredentialsManager::getAuthUrl().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetGraphqlUrl(JNIEnv* env, jclass /* clazz */) {
    return env->NewStringUTF(CredentialsManager::getGraphqlUrl().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetGraphqlWsUrl(JNIEnv* env, jclass /* clazz */) {
    return env->NewStringUTF(CredentialsManager::getGraphqlWsUrl().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetStorageUrl(JNIEnv* env, jclass /* clazz */) {
    return env->NewStringUTF(CredentialsManager::getStorageUrl().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetFunctionsUrl(JNIEnv* env, jclass /* clazz */) {
    return env->NewStringUTF(CredentialsManager::getFunctionsUrl().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetHasuraUrl(JNIEnv* env, jclass /* clazz */) {
    return env->NewStringUTF(CredentialsManager::getHasuraUrl().c_str());
}

}
