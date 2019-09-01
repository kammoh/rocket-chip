#include "rocc.h"
#include "mmu.h"
#include "sha3_model.h"

class sha3_rocc_t : public rocc_t {
public:
  const char *name() override { return "sha3"; }

  reg_t custom0(rocc_insn_t insn, reg_t xs1, reg_t xs2) override {
    switch (insn.funct) {
      case 0:
        DPRINTF("[SHA3 RoCC] sha3_init: rate=%lu\n", xs1);
        assert(xs1 % 8 == 0);
        keccak->init(xs1);
        break;
      case 1:
        addr = xs1;
        len = xs2;
        num_permutations = 0;
        DPRINTF("[SHA3 RoCC] sha3_absorb: src=%lx size=%lu\n", addr, len);
        assert(len % 8 == 0);
        buffer = (unsigned char *) malloc(len);
        for (reg_t i = 0; i < len; i++)
          buffer[i] = p->get_mmu()->load_uint8(addr + i);
        num_permutations = keccak->absorb(buffer, len);

        free(buffer);

        p->get_state()->midlecycles += (2 * ((len + 7) / 8) + num_permutations * (24 + 2) + 4);

        keccak->dump_state();

        break;
      case 2:
        addr = xs1;
        len = xs2;
        num_permutations = 0;
        DPRINTF("[SHA3 RoCC] sha3_squeezeblocks: dst=%lx size=%lu\n", addr, len);
        assert(len % 8 == 0);
        {
          uint64_t nblocks = (len + keccak->rate - 1) / keccak->rate;// ceil(len/rate)
          buffer = (unsigned char *) malloc(nblocks * keccak->rate);
          num_permutations = keccak->squeezeblocks(buffer, nblocks);

          for (reg_t i = 0; i < len; i++)
            p->get_mmu()->store_uint8(addr + i, buffer[i]);
          free(buffer);
        }

        keccak->dump_state();

        p->get_state()->midlecycles += ((len + 7) / 8 + num_permutations * (24 + 2) + 4);
        break;
      default:
        illegal_instruction();
    }

    return 0;
  }

  sha3_rocc_t() : keccak(new Keccak), buffer(nullptr), addr(0), len(0), num_permutations(0) {
    DPRINTF("sha3_rocc_t constructor\n");
  }

private:
  Keccak *keccak;
  unsigned char *buffer;
  reg_t addr;
  reg_t len;
  int num_permutations;
};

REGISTER_EXTENSION(sha3_rocc, []() { return new sha3_rocc_t; })
