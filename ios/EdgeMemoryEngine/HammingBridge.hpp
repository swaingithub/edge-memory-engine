#ifndef HammingBridge_hpp
#define HammingBridge_hpp

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int compute_hamming_distance_simd(const uint8_t* q, const uint8_t* t);
void batch_compute_distances_simd(const uint8_t* query, 
                                  const uint8_t* candidate_matrix, 
                                  int count, 
                                  int32_t* out_distances);

#ifdef __cplusplus
}
#endif

#endif
