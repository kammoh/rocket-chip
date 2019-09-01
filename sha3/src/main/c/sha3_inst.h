#ifndef __SHA3_ROCC_H__
#define __SHA3_ROCC_H__

#define STR1(x) #x
#define STR(x) STR1(x)

#define XBYTES sizeof(unsigned long) // should be set to the number of bytes in a machine word


/***************************************************
 * RoCC custom instruction macros
 ***************************************************/

#define CUSTOMX(X, flags, rd, rs1, rs2, func)  ".insn r CUSTOM_" STR(X) ", " STR(flags)", " STR(func) ", " \
          STR(rd) ", " STR(rs1) ", " STR(rs2)

#define ROCC_INSTRUCTION_DSS(X, rd, rs1, rs2, func) asm volatile( \
          CUSTOMX(X, 7, %[rd], %[rs1], %[rs2], func) : [rd] "=r"(rd) : [rs1] "r"(rs1), [rs2] "r"(rs2));

#define ROCC_INSTRUCTION_S(X, rs1, func) asm volatile( \
          CUSTOMX(X, 2, x0, %[rs1], x0, func) : : [rs1] "r"(rs1));


/************************************************
 *
 * sha3_init: Initialize sha3 accelerator
 *
 * @param rate
 *
 ************************************************/

static inline void sha3_init(unsigned int rate) {
  ROCC_INSTRUCTION_S(0, rate, 0)
}

static inline int sha3_absorb(volatile const void *src, size_t size) {
  volatile int ret;
  ROCC_INSTRUCTION_DSS(0, ret, src, size, 1)
  return ret;
}

/* utility function */
static inline int sha3_absorb_full(unsigned int rate, volatile const unsigned char *src, size_t size, unsigned char p) {
  sha3_init(rate);

  size_t len = size & ~(XBYTES - 1);
  if (len > 0) {
    int ret = sha3_absorb(src, len);
    if (!ret)
      return ret;
  }
  int remain_bytes = (int) (size & (XBYTES - 1));
  unsigned long last_word = 0;
  for (int i = 0; i < remain_bytes; i++) {
    last_word |= (unsigned long) src[len + i] << (unsigned long) (XBYTES * i);
  }
  last_word |= (unsigned long) p << (unsigned long) (XBYTES * remain_bytes);

  return sha3_absorb(&last_word, XBYTES);
}

static inline int sha3_squeeze(volatile void *dst, size_t size) {
  volatile int ret;
  ROCC_INSTRUCTION_DSS(0, ret, dst, size, 2)
//  asm volatile("fence");
  return ret;
}

#endif