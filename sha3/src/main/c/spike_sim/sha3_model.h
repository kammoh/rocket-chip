#ifndef __SHA3_MODEL_H_
#define __SHA3_MODEL_H_

#ifndef DEBUG
#define DEBUG 0
#else
#undef DEBUG
#define DEBUG 1
#endif

#define DPRINTF(fmt, ...) do { if (DEBUG) fprintf(stdout, "[%s:%d:%s()] " fmt, __FILE__, \
                                __LINE__, __func__, ##__VA_ARGS__); } while (0)


class Keccak {
public:
  Keccak ();
/*************************************************
* Name:        keccak_init
*
* Description: Initialize by zeroeing the state and saving rate
*
* Arguments:   - uint64_t *state:             pointer to (uninitialized) output Keccak state
*              - unsigned int r:          rate in bytes (e.g., 168 for SHAKE128)
**************************************************/
  void init(unsigned int r);


/*************************************************
* Name:        keccak_absorb
*
* Description: Absorb step of Keccak;
*
* Arguments:   - uint64_t *state:             pointer to output Keccak state
*              - const unsigned char *m:  pointer to input to be absorbed into state
*              - unsigned long long mlen: length of input in bytes
**************************************************/
  int absorb(const unsigned char *m, unsigned long long int mlen);

/*************************************************
* Name:        keccak_squeezeblocks
*
* Description: Squeeze step of Keccak. Squeezes full blocks of rate bytes each.
*              Modifies the state. Can be called multiple times to keep squeezing,
*              i.e., is incremental.
*
* Arguments:   - unsigned char *h:               pointer to output blocks
*              - unsigned long long int nblocks: number of blocks to be squeezed (written to h)
*              - uint64_t *state:                    pointer to in/output Keccak state
*              - unsigned int rate:                 rate in bytes (e.g., 168 for SHAKE128)
**************************************************/
  int squeezeblocks(unsigned char *h, unsigned long long int nblocks);

  void dump_state();
  unsigned int rate;
private:
  uint64_t state[25];
  unsigned int prev_xored_bytes;
  bool have_been_sqeezing = false;

  void statePermute();
};

#endif