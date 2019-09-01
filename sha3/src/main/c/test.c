#include <stdio.h>
#include <assert.h>

#include "perf.h"
#include "fips202.h"


int main() {
  const size_t MESSAGE_SIZE = 1 * 1024;
  unsigned char message[MESSAGE_SIZE];

  unsigned char hash_0[SHA3_512_RATE];
  unsigned char hash_1[SHA3_512_RATE];

  for (size_t i = 0; i < sizeof(message); i++) {
    message[i] = i % 256;
  }

  printf("starting regular sha3_512\n");

  uint64_t cycles_0 = perf_rdcycle();
  uint64_t insts_0 = perf_rdinstret();

  sha3_512(message, hash_0, sizeof(message));

  cycles_0 = perf_rdcycle() - cycles_0;
  insts_0 = perf_rdinstret() - insts_0;

  printf("\n[regular]\n   cycles:               %10lu  \n   instructions retired: %10lu\n\n", cycles_0, insts_0);


  printf("starting accelerated sha3_512_rocc\n");
  uint64_t cycles_1 = perf_rdcycle();
  uint64_t insts_1 = perf_rdinstret();

  sha3_512_rocc(message, hash_1, sizeof(message));

  cycles_1 = perf_rdcycle() - cycles_1;
  insts_1 = perf_rdinstret() - insts_1;

  printf("\n[accelerated]\n   cycles:               %10lu  \n   instructions retired: %10lu\n\n", cycles_1, insts_1);

  for (size_t i = 0; i < sizeof(hash_0); i++) {
    assert(hash_0[i] == hash_1[i]);
  }


  printf("success!\n");

  printf("speedup: %0.3f\n", (double) cycles_0 / cycles_1);
}