#include <jni.h>
#include <arm_neon.h>
#include <cstdint>

extern "C" JNIEXPORT jint JNICALL
Java_com_memory_engine_MemoryEngine_computeHammingDistanceNative(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray query_bytes,
    jbyteArray target_bytes
) {
    // 64 bytes for each embedding (512 bits)
    jbyte* query = env->GetByteArrayElements(query_bytes, nullptr);
    jbyte* target = env->GetByteArrayElements(target_bytes, nullptr);

    int distance = 0;
    
    // We can process 16 bytes (128 bits) at a time using uint8x16_t
    uint8_t* q = reinterpret_cast<uint8_t*>(query);
    uint8_t* t = reinterpret_cast<uint8_t*>(target);

    for (int i = 0; i < 64; i += 16) {
        uint8x16_t vec_q = vld1q_u8(q + i);
        uint8x16_t vec_t = vld1q_u8(t + i);
        
        // XOR vectors
        uint8x16_t vec_xor = veorq_u8(vec_q, vec_t);
        
        // Count bits
        uint8x16_t vec_cnt = vcntq_u8(vec_xor);
        
        // Sum across the vector
        // vpaddlq_u8 -> uint16x8_t
        // vpaddlq_u16 -> uint32x4_t
        // vpaddlq_u32 -> uint64x2_t
        // Then extract and add.
        // Or simpler, just cast to uint64_t and use __builtin_popcountll for the 64 byte blocks if we don't want to use vcntq tree sum.
        // Actually since we have NEON vcntq_u8, we can just do:
        uint16x8_t sum1 = vpaddlq_u8(vec_cnt);
        uint32x4_t sum2 = vpaddlq_u16(sum1);
        uint32_t total = vgetq_lane_u32(sum2, 0) + vgetq_lane_u32(sum2, 1) + 
                         vgetq_lane_u32(sum2, 2) + vgetq_lane_u32(sum2, 3);
        
        distance += total;
    }

    env->ReleaseByteArrayElements(query_bytes, query, JNI_ABORT);
    env->ReleaseByteArrayElements(target_bytes, target, JNI_ABORT);

    return distance;
}
