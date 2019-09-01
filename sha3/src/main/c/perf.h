#ifndef _PERF_H
#define _PERF_H

#define read_csr_safe(reg) ({ register long __tmp asm("a0"); \
  asm volatile ("csrr %0, " #reg : "=r"(__tmp)); \
  __tmp; })

static inline uint64_t perf_rdcycle() {
  volatile uint32_t tr;
  asm volatile ("rdcycle %[tr]":[tr]"=r"(tr));
  return tr;
}

static inline uint64_t perf_rdinstret() {
  volatile uint32_t tr;
  asm volatile ("rdinstret %[tr]":[tr]"=r"(tr));
  return tr;
}

#endif // _PERF_H