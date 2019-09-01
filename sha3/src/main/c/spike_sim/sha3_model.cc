/* Based on the public domain implementation in
 * crypto_hash/keccakc512/simple/ from http://bench.cr.yp.to/supercop.html
 * by Ronny Van Keer
 * and the public domain "TweetFips202" implementation
 * from https://twitter.com/tweetfips202
 * by Gilles Van Assche, Daniel J. Bernstein, and Peter Schwabe */

#include <cstdint>
#include <cassert>
#include <cstdio>

#include "sha3_model.h"

#define NROUNDS 24
#define ROL(a, offset) ((a << offset) ^ (a >> (64-offset)))


/*************************************************
* Name:        load64
*
* Description: Load 8 bytes into uint64_t in little-endian order
*
* Arguments:   - const unsigned char *x: pointer to input byte array
*
* Returns the loaded 64-bit unsigned integer
**************************************************/
static uint64_t load64(const unsigned char *x) {
  unsigned long long rate = 0, i;

  for (i = 0; i < 8; ++i) {
    rate |= (unsigned long long) x[i] << 8 * i;
  }
  return rate;
}

/*************************************************
* Name:        store64
*
* Description: Store a 64-bit integer to a byte array in little-endian order
*
* Arguments:   - uint8_t *x: pointer to the output byte array
*              - uint64_t u: input 64-bit unsigned integer
**************************************************/
static void store64(uint8_t *x, uint64_t u) {
  unsigned int i;

  for (i = 0; i < 8; ++i) {
    x[i] = u;
    u >>= 8U;
  }
}

void Keccak::init(unsigned int r) {
  for (unsigned long long & word : state)
    word = 0;
  rate = r;
  prev_xored_bytes = 0;
  have_been_sqeezing = false;
}

/**
 *
 * @param m
 * @param mlen
 * @return number of calls to statePermute
 */
int Keccak::absorb(const unsigned char *m, unsigned long long int mlen) {
  unsigned long long i;

  assert(mlen % 8 == 0);

  int perms = 0;

  DPRINTF("mlen=%llu prev_xored_bytes=%d\n", mlen, prev_xored_bytes);

  while (prev_xored_bytes + mlen > rate) {
    DPRINTF("[absorb] mlen=%llu\n", mlen);
    for (i = 0; i < (rate - prev_xored_bytes) / 8; ++i) {
      state[i] ^= load64(m + 8 * i);
    }

    statePermute();
    perms++;
    mlen -= rate - prev_xored_bytes;
    m += rate - prev_xored_bytes;
    prev_xored_bytes = 0;
  }

  for (i = 0; i < mlen / 8; ++i)
    state[i + (prev_xored_bytes / 8)] ^= load64(m + 8 * i);

  prev_xored_bytes += i * 8;

  DPRINTF("done with %d perms. prev_xored_bytes=%d\n", perms, prev_xored_bytes);

  return perms;
}

int Keccak::squeezeblocks(unsigned char *h, unsigned long long int nblocks) {
  unsigned int i;
  int perms = 0;
  unsigned int rateLanes = rate >> 3UL;
  DPRINTF("nblock=%llu\n", nblocks);
  if (!have_been_sqeezing) {
    state[rateLanes - 1] ^= (1UL << 63UL);
    DPRINTF("finalized. state after finalize:\n");
    dump_state();
    have_been_sqeezing = true;
    prev_xored_bytes = 0;
  }
  while (nblocks > 0) {
    statePermute();
    perms++;
    for (i = 0; i < rateLanes; i++) {
      store64(h + 8 * i, state[i]);
    }
    h += rate;
    nblocks--;
  }

  DPRINTF("done with %d perms\n", perms);
  return perms;
}

/* Keccak round constants */
const uint64_t KeccakF_RoundConstants[NROUNDS] = {
    (uint64_t) 0x0000000000000001ULL,
    (uint64_t) 0x0000000000008082ULL,
    (uint64_t) 0x800000000000808aULL,
    (uint64_t) 0x8000000080008000ULL,
    (uint64_t) 0x000000000000808bULL,
    (uint64_t) 0x0000000080000001ULL,
    (uint64_t) 0x8000000080008081ULL,
    (uint64_t) 0x8000000000008009ULL,
    (uint64_t) 0x000000000000008aULL,
    (uint64_t) 0x0000000000000088ULL,
    (uint64_t) 0x0000000080008009ULL,
    (uint64_t) 0x000000008000000aULL,
    (uint64_t) 0x000000008000808bULL,
    (uint64_t) 0x800000000000008bULL,
    (uint64_t) 0x8000000000008089ULL,
    (uint64_t) 0x8000000000008003ULL,
    (uint64_t) 0x8000000000008002ULL,
    (uint64_t) 0x8000000000000080ULL,
    (uint64_t) 0x000000000000800aULL,
    (uint64_t) 0x800000008000000aULL,
    (uint64_t) 0x8000000080008081ULL,
    (uint64_t) 0x8000000000008080ULL,
    (uint64_t) 0x0000000080000001ULL,
    (uint64_t) 0x8000000080008008ULL
};

void Keccak::statePermute() {
  int round;

  uint64_t Aba, Abe, Abi, Abo, Abu;
  uint64_t Aga, Age, Agi, Ago, Agu;
  uint64_t Aka, Ake, Aki, Ako, Aku;
  uint64_t Ama, Ame, Ami, Amo, Amu;
  uint64_t Asa, Ase, Asi, Aso, Asu;
  uint64_t BCa, BCe, BCi, BCo, BCu;
  uint64_t Da, De, Di, Do, Du;
  uint64_t Eba, Ebe, Ebi, Ebo, Ebu;
  uint64_t Ega, Ege, Egi, Ego, Egu;
  uint64_t Eka, Eke, Eki, Eko, Eku;
  uint64_t Ema, Eme, Emi, Emo, Emu;
  uint64_t Esa, Ese, Esi, Eso, Esu;

  //copyFromState(A, state)
  Aba = state[0];
  Abe = state[1];
  Abi = state[2];
  Abo = state[3];
  Abu = state[4];
  Aga = state[5];
  Age = state[6];
  Agi = state[7];
  Ago = state[8];
  Agu = state[9];
  Aka = state[10];
  Ake = state[11];
  Aki = state[12];
  Ako = state[13];
  Aku = state[14];
  Ama = state[15];
  Ame = state[16];
  Ami = state[17];
  Amo = state[18];
  Amu = state[19];
  Asa = state[20];
  Ase = state[21];
  Asi = state[22];
  Aso = state[23];
  Asu = state[24];

  for (round = 0; round < NROUNDS; round += 2) {
    //    prepareTheta
    BCa = Aba ^ Aga ^ Aka ^ Ama ^ Asa;
    BCe = Abe ^ Age ^ Ake ^ Ame ^ Ase;
    BCi = Abi ^ Agi ^ Aki ^ Ami ^ Asi;
    BCo = Abo ^ Ago ^ Ako ^ Amo ^ Aso;
    BCu = Abu ^ Agu ^ Aku ^ Amu ^ Asu;

    //thetaRhoPiChiIotaPrepareTheta(round  , A, E)
    Da = BCu ^ ROL(BCe, 1U);
    De = BCa ^ ROL(BCi, 1U);
    Di = BCe ^ ROL(BCo, 1U);
    Do = BCi ^ ROL(BCu, 1U);
    Du = BCo ^ ROL(BCa, 1U);

    Aba ^= Da;
    BCa = Aba;
    Age ^= De;
    BCe = ROL(Age, 44U);
    Aki ^= Di;
    BCi = ROL(Aki, 43U);
    Amo ^= Do;
    BCo = ROL(Amo, 21U);
    Asu ^= Du;
    BCu = ROL(Asu, 14U);
    Eba = BCa ^ ((~BCe) & BCi);
    Eba ^= (uint64_t) KeccakF_RoundConstants[round];
    Ebe = BCe ^ ((~BCi) & BCo);
    Ebi = BCi ^ ((~BCo) & BCu);
    Ebo = BCo ^ ((~BCu) & BCa);
    Ebu = BCu ^ ((~BCa) & BCe);

    Abo ^= Do;
    BCa = ROL(Abo, 28U);
    Agu ^= Du;
    BCe = ROL(Agu, 20U);
    Aka ^= Da;
    BCi = ROL(Aka, 3U);
    Ame ^= De;
    BCo = ROL(Ame, 45U);
    Asi ^= Di;
    BCu = ROL(Asi, 61U);
    Ega = BCa ^ ((~BCe) & BCi);
    Ege = BCe ^ ((~BCi) & BCo);
    Egi = BCi ^ ((~BCo) & BCu);
    Ego = BCo ^ ((~BCu) & BCa);
    Egu = BCu ^ ((~BCa) & BCe);

    Abe ^= De;
    BCa = ROL(Abe, 1U);
    Agi ^= Di;
    BCe = ROL(Agi, 6U);
    Ako ^= Do;
    BCi = ROL(Ako, 25U);
    Amu ^= Du;
    BCo = ROL(Amu, 8U);
    Asa ^= Da;
    BCu = ROL(Asa, 18U);
    Eka = BCa ^ ((~BCe) & BCi);
    Eke = BCe ^ ((~BCi) & BCo);
    Eki = BCi ^ ((~BCo) & BCu);
    Eko = BCo ^ ((~BCu) & BCa);
    Eku = BCu ^ ((~BCa) & BCe);

    Abu ^= Du;
    BCa = ROL(Abu, 27U);
    Aga ^= Da;
    BCe = ROL(Aga, 36U);
    Ake ^= De;
    BCi = ROL(Ake, 10U);
    Ami ^= Di;
    BCo = ROL(Ami, 15U);
    Aso ^= Do;
    BCu = ROL(Aso, 56U);
    Ema = BCa ^ ((~BCe) & BCi);
    Eme = BCe ^ ((~BCi) & BCo);
    Emi = BCi ^ ((~BCo) & BCu);
    Emo = BCo ^ ((~BCu) & BCa);
    Emu = BCu ^ ((~BCa) & BCe);

    Abi ^= Di;
    BCa = ROL(Abi, 62U);
    Ago ^= Do;
    BCe = ROL(Ago, 55U);
    Aku ^= Du;
    BCi = ROL(Aku, 39U);
    Ama ^= Da;
    BCo = ROL(Ama, 41U);
    Ase ^= De;
    BCu = ROL(Ase, 2U);
    Esa = BCa ^ ((~BCe) & BCi);
    Ese = BCe ^ ((~BCi) & BCo);
    Esi = BCi ^ ((~BCo) & BCu);
    Eso = BCo ^ ((~BCu) & BCa);
    Esu = BCu ^ ((~BCa) & BCe);

    //    prepareTheta
    BCa = Eba ^ Ega ^ Eka ^ Ema ^ Esa;
    BCe = Ebe ^ Ege ^ Eke ^ Eme ^ Ese;
    BCi = Ebi ^ Egi ^ Eki ^ Emi ^ Esi;
    BCo = Ebo ^ Ego ^ Eko ^ Emo ^ Eso;
    BCu = Ebu ^ Egu ^ Eku ^ Emu ^ Esu;

    //thetaRhoPiChiIotaPrepareTheta(round+1, E, A)
    Da = BCu ^ ROL(BCe, 1U);
    De = BCa ^ ROL(BCi, 1U);
    Di = BCe ^ ROL(BCo, 1U);
    Do = BCi ^ ROL(BCu, 1U);
    Du = BCo ^ ROL(BCa, 1U);

    Eba ^= Da;
    BCa = Eba;
    Ege ^= De;
    BCe = ROL(Ege, 44U);
    Eki ^= Di;
    BCi = ROL(Eki, 43U);
    Emo ^= Do;
    BCo = ROL(Emo, 21U);
    Esu ^= Du;
    BCu = ROL(Esu, 14U);
    Aba = BCa ^ ((~BCe) & BCi);
    Aba ^= (uint64_t) KeccakF_RoundConstants[round + 1];
    Abe = BCe ^ ((~BCi) & BCo);
    Abi = BCi ^ ((~BCo) & BCu);
    Abo = BCo ^ ((~BCu) & BCa);
    Abu = BCu ^ ((~BCa) & BCe);

    Ebo ^= Do;
    BCa = ROL(Ebo, 28U);
    Egu ^= Du;
    BCe = ROL(Egu, 20U);
    Eka ^= Da;
    BCi = ROL(Eka, 3U);
    Eme ^= De;
    BCo = ROL(Eme, 45U);
    Esi ^= Di;
    BCu = ROL(Esi, 61U);
    Aga = BCa ^ ((~BCe) & BCi);
    Age = BCe ^ ((~BCi) & BCo);
    Agi = BCi ^ ((~BCo) & BCu);
    Ago = BCo ^ ((~BCu) & BCa);
    Agu = BCu ^ ((~BCa) & BCe);

    Ebe ^= De;
    BCa = ROL(Ebe, 1U);
    Egi ^= Di;
    BCe = ROL(Egi, 6U);
    Eko ^= Do;
    BCi = ROL(Eko, 25U);
    Emu ^= Du;
    BCo = ROL(Emu, 8U);
    Esa ^= Da;
    BCu = ROL(Esa, 18U);
    Aka = BCa ^ ((~BCe) & BCi);
    Ake = BCe ^ ((~BCi) & BCo);
    Aki = BCi ^ ((~BCo) & BCu);
    Ako = BCo ^ ((~BCu) & BCa);
    Aku = BCu ^ ((~BCa) & BCe);

    Ebu ^= Du;
    BCa = ROL(Ebu, 27U);
    Ega ^= Da;
    BCe = ROL(Ega, 36U);
    Eke ^= De;
    BCi = ROL(Eke, 10U);
    Emi ^= Di;
    BCo = ROL(Emi, 15U);
    Eso ^= Do;
    BCu = ROL(Eso, 56U);
    Ama = BCa ^ ((~BCe) & BCi);
    Ame = BCe ^ ((~BCi) & BCo);
    Ami = BCi ^ ((~BCo) & BCu);
    Amo = BCo ^ ((~BCu) & BCa);
    Amu = BCu ^ ((~BCa) & BCe);

    Ebi ^= Di;
    BCa = ROL(Ebi, 62U);
    Ego ^= Do;
    BCe = ROL(Ego, 55U);
    Eku ^= Du;
    BCi = ROL(Eku, 39U);
    Ema ^= Da;
    BCo = ROL(Ema, 41U);
    Ese ^= De;
    BCu = ROL(Ese, 2U);
    Asa = BCa ^ ((~BCe) & BCi);
    Ase = BCe ^ ((~BCi) & BCo);
    Asi = BCi ^ ((~BCo) & BCu);
    Aso = BCo ^ ((~BCu) & BCa);
    Asu = BCu ^ ((~BCa) & BCe);
  }

  //copyToState(state, A)
  state[0] = Aba;
  state[1] = Abe;
  state[2] = Abi;
  state[3] = Abo;
  state[4] = Abu;
  state[5] = Aga;
  state[6] = Age;
  state[7] = Agi;
  state[8] = Ago;
  state[9] = Agu;
  state[10] = Aka;
  state[11] = Ake;
  state[12] = Aki;
  state[13] = Ako;
  state[14] = Aku;
  state[15] = Ama;
  state[16] = Ame;
  state[17] = Ami;
  state[18] = Amo;
  state[19] = Amu;
  state[20] = Asa;
  state[21] = Ase;
  state[22] = Asi;
  state[23] = Aso;
  state[24] = Asu;

}

Keccak::Keccak() : rate(0), state{0xffffffffff, 0xff13, 1, 2, 3, 4, 5, 6, 0x1cfffffcabcaffff, 0xfffacffacfff, 3},
                   prev_xored_bytes(0), have_been_sqeezing(false) {

}

void Keccak::dump_state() {
#if DEBUG
  for (int i = 0; i < 25; i++) {
    printf("  %016llX", state[i]);
    if (i % 8 == 7 || i == 24)
      printf("\n");
  }
#endif
}
