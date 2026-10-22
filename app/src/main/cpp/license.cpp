#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "NativeLicense"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_granter_License_nativeVerify(
        JNIEnv* env, jobject thiz,
        jstring payload_, jstring sigB64_, jstring pubKeyB64_) {

    jclass sigClass = env->FindClass("java/security/Signature");
    if (sigClass == nullptr) return JNI_FALSE;

    jmethodID getInstance = env->GetStaticMethodID(sigClass, "getInstance", "(Ljava/lang/String;)Ljava/security/Signature;");
    jstring algo = env->NewStringUTF("SHA256withRSA");
    jobject sigObj = env->CallStaticObjectMethod(sigClass, getInstance, algo);
    env->DeleteLocalRef(algo);

    if (sigObj == nullptr) return JNI_FALSE;

    jclass kfClass = env->FindClass("java/security/KeyFactory");
    jmethodID kfGetInstance = env->GetStaticMethodID(kfClass, "getInstance", "(Ljava/lang/String;)Ljava/security/KeyFactory;");
    jstring rsa = env->NewStringUTF("RSA");
    jobject kfObj = env->CallStaticObjectMethod(kfClass, kfGetInstance, rsa);
    env->DeleteLocalRef(rsa);

    jclass x509Class = env->FindClass("java/security/spec/X509EncodedKeySpec");
    jmethodID x509Init = env->GetMethodID(x509Class, "<init>", "([B)V");

    jclass b64Class = env->FindClass("android/util/Base64");
    jmethodID b64Decode = env->GetStaticMethodID(b64Class, "decode", "(Ljava/lang/String;I)[B");
    
    jbyteArray pubKeyBytes = (jbyteArray)env->CallStaticObjectMethod(b64Class, b64Decode, pubKeyB64_, 0);
    jobject x509Spec = env->NewObject(x509Class, x509Init, pubKeyBytes);

    jmethodID generatePublic = env->GetMethodID(kfClass, "generatePublic", "(Ljava/security/spec/KeySpec;)Ljava/security/PublicKey;");
    jobject pubKeyObj = env->CallObjectMethod(kfObj, generatePublic, x509Spec);
    
    if (pubKeyObj == nullptr) {
        LOGD("Public key invalid");
        return JNI_FALSE;
    }

    jmethodID initVerify = env->GetMethodID(sigClass, "initVerify", "(Ljava/security/PublicKey;)V");
    env->CallVoidMethod(sigObj, initVerify, pubKeyObj);

    jmethodID updateMethod = env->GetMethodID(sigClass, "update", "([B)V");
    jbyteArray payloadBytes = env->NewByteArray(env->GetStringUTFLength(payload_));
    env->SetByteArrayRegion(payloadBytes, 0, env->GetStringUTFLength(payload_), (const jbyte*)env->GetStringUTFChars(payload_, 0));
    env->CallVoidMethod(sigObj, updateMethod, payloadBytes);

    jbyteArray sigBytes = (jbyteArray)env->CallStaticObjectMethod(b64Class, b64Decode, sigB64_, 0);

    jmethodID verifyMethod = env->GetMethodID(sigClass, "verify", "([B)Z");
    jboolean result = env->CallBooleanMethod(sigObj, verifyMethod, sigBytes);

    return result;
}
