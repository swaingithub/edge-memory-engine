#include "HammingBridge.hpp"
#include <arm_neon.h>

extern "C" int compute_hamming_distance_simd(const uint8_t* q, const uint8_t* t) {
    int distance = 0;
    for (int i = 0; i < 64; i += 16) {
        uint8x16_t vec_q = vld1q_u8(q + i);
        uint8x16_t vec_t = vld1q_u8(t + i);
        
        uint8x16_t vec_xor = veorq_u8(vec_q, vec_t);
        uint8x16_t vec_cnt = vcntq_u8(vec_xor);
        
        uint16x8_t sum1 = vpaddlq_u8(vec_cnt);
        uint32x4_t sum2 = vpaddlq_u16(sum1);
        distance += (vgetq_lane_u32(sum2, 0) + vgetq_lane_u32(sum2, 1) + 
                     vgetq_lane_u32(sum2, 2) + vgetq_lane_u32(sum2, 3));
    }
    return distance;
}

extern "C" void batch_compute_distances_simd(const uint8_t* query, 
                                            const uint8_t* candidate_matrix, 
                                            int count, 
                                            int32_t* out_distances) {
    for (int i = 0; i < count; i++) {
        out_distances[i] = compute_hamming_distance_simd(query, candidate_matrix + (i * 64));
    }
}
