#ifndef OPENCHAT_CREDENTIALS_MANAGER_H
#define OPENCHAT_CREDENTIALS_MANAGER_H

#include <jni.h>
#include <string>
#include <cstdint>
#include <vector>

namespace OpenChatSecurity {

/**
 * High-security binary & hexadecimal credential manager for OpenChat.
 * Deobfuscates sensitive environment variables, service endpoints, and
 * Nhost configuration keys dynamically at runtime into secure memory.
 */
class CredentialsManager {
public:
    static std::string getSubdomain();
    static std::string getRegion();
    static std::string getAuthUrl();
    static std::string getGraphqlUrl();
    static std::string getGraphqlWsUrl();
    static std::string getStorageUrl();
    static std::string getFunctionsUrl();
    static std::string getHasuraUrl();

private:
    static std::string decodeHex(const char* hexStr);
    static std::string decodeBinary(const uint8_t* encodedBytes, size_t length, uint8_t xorKey = 0x5A);
};

} // namespace OpenChatSecurity

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetSubdomain(JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetRegion(JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetAuthUrl(JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetGraphqlUrl(JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetGraphqlWsUrl(JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetStorageUrl(JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetFunctionsUrl(JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL
Java_com_msi_openchat_core_security_NativeCredentialsManager_nativeGetHasuraUrl(JNIEnv* env, jclass clazz);

#ifdef __cplusplus
}
#endif

#endif // OPENCHAT_CREDENTIALS_MANAGER_H
