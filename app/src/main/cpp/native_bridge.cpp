#include <jni.h>
#include <cstdint>

extern "C" int compute_hamming_distance_simd(const uint8_t* q, const uint8_t* t);

extern "C" JNIEXPORT jint JNICALL
Java_com_edgememory_data_native_NativeHamming_computeDistance(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray query_bytes,
    jbyteArray target_bytes
) {
    if (query_bytes == nullptr || target_bytes == nullptr) return -1;
    if (env->GetArrayLength(query_bytes) < 64 || env->GetArrayLength(target_bytes) < 64) return -1;

    jbyte* query = env->GetByteArrayElements(query_bytes, nullptr);
    jbyte* target = env->GetByteArrayElements(target_bytes, nullptr);

    int distance = compute_hamming_distance_simd(
        reinterpret_cast<const uint8_t*>(query),
        reinterpret_cast<const uint8_t*>(target)
    );

    env->ReleaseByteArrayElements(query_bytes, query, JNI_ABORT);
    env->ReleaseByteArrayElements(target_bytes, target, JNI_ABORT);

    return distance;
}

extern "C" JNIEXPORT void JNICALL
Java_com_edgememory_data_native_NativeHamming_batchComputeDistances(
        JNIEnv* env,
        jclass clazz,
        jbyteArray queryBlob,
        jobjectArray candidateBlobsArray,
        jintArray outDistances) {

    if (queryBlob == nullptr || candidateBlobsArray == nullptr || outDistances == nullptr) return;
    if (env->GetArrayLength(queryBlob) < 64) return;

    jbyte* qPtr = env->GetByteArrayElements(queryBlob, nullptr);
    const uint8_t* query = reinterpret_cast<const uint8_t*>(qPtr);

    jsize count = env->GetArrayLength(candidateBlobsArray);
    jint* distOut = env->GetIntArrayElements(outDistances, nullptr);

    for (int i = 0; i < count; i++) {
        auto cArray = (jbyteArray)env->GetObjectArrayElement(candidateBlobsArray, i);
        if (cArray != nullptr && env->GetArrayLength(cArray) >= 64) {
            jbyte* cPtr = env->GetByteArrayElements(cArray, nullptr);
            distOut[i] = compute_hamming_distance_simd(query, reinterpret_cast<const uint8_t*>(cPtr));
            env->ReleaseByteArrayElements(cArray, cPtr, JNI_ABORT);
        } else {
            distOut[i] = 512; // Assign max distance penalty if vector is malformed
        }
        if (cArray != nullptr) {
            env->DeleteLocalRef(cArray);
        }
    }

    env->ReleaseByteArrayElements(queryBlob, qPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(outDistances, distOut, 0);
}
