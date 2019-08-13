// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

#include "verilated.h"

#if VM_TRACE

#include <memory>
#include "verilated_vcd_c.h"

#endif

#include <fesvr/dtm.h>
#include "remote_bitbang.h"
#include "verilator.h"
#include <iostream>
#include <string>
#include <cstdio>
#include <cstdlib>
#include <csignal>
#include <fcntl.h>
#include <unistd.h>
#include <getopt.h>

#if VM_TRACE
#if VM_TRACE_FST
#include "verilated_fst_c.h"
#else
#include "verilated_vcd_c.h"
#endif

#ifndef TEST_HARNESS

class TEST_HARNESS : public VerilatedModule {
public:
private:
  VL_UNCOPYABLE(TEST_HARNESS);  ///< Copying not allowed
public:
  // PORTS
  // The application code writes and reads these signals to
  // propagate new values into/out from the Verilated model.
  VL_IN8(clock,0,0);
  VL_IN8(reset,0,0);
  // Begin mtask footprint  all: 41
  VL_OUT8(io_success,0,0);

  /// Construct the model; called by application code
  /// The special name  may be used to make a wrapper with a
  /// single model invisible with respect to DPI scope names.
  TEST_HARNESS(const char* name="TOP");
  /// Destroy the model; called (often implicitly) by application code
  ~TEST_HARNESS();
  /// Trace signals in the model; called by application code
#ifdef VM_TRACE_FST
  void trace(VerilatedFstC* tfp, int levels, int options=0);
#else
  void trace(VerilatedVcdC* tfp, int levels, int options=0);
#endif
  // API METHODS
  /// Evaluate the model.  Application must call when inputs change.
  void eval();
  /// Simulation complete, run final blocks.  Application must call on completion.
  void final();
};
#endif

class VerilatedTrace {
public:

  explicit VerilatedTrace(): tfp(nullptr) {
    Verilated::traceEverOn(true); // Verilator must compute traced signals
  }

  int activate(const char *trace_file_name) {
    filename = trace_file_name;
  }

  int setTrace(TEST_HARNESS *tile){
    if(filename.empty()){ // not activated
      return 1;
    }
#ifdef VM_TRACE_FST
    tfp = new VerilatedFstC();
#else
    if(vcdfile)
      fclose(vcdfile);
    vcdfile = strcmp(optarg, "-") == 0 ? stdout : fopen(filename.c_str(), "w");
    if (!vcdfile) {
      std::cerr << "Unable to open " << filename << " for VCD write\n";
      exit(1);
    }
    tfp = new VerilatedVcdC(new VerilatedVcdFILE(vcdfile));
#endif
    tile->trace(tfp, 99);  // Trace 99 levels of hierarchy
#ifdef VM_TRACE_FST
    tfp->open(filename.c_str());
    if(!tfp->isOpen()){
      std::cerr << "failed to open FST trace file!" << std::endl;
      exit(-1);
    }
#else
    tfp->open("");
#endif
    return 0;
  }

  void close()  {
    if (tfp)
      tfp->close();
#ifndef VM_TRACE_FST
    if (vcdfile)
      fclose(vcdfile);
#endif
  }

  inline void dump(uint64_t timestamp) {
    if (tfp)
      tfp->dump(static_cast<vluint64_t>(timestamp));
  }

  ~VerilatedTrace() {
    close();
  }

  std::string filename;
#ifdef VM_TRACE_FST
  VerilatedFstC *tfp;
#else
  VerilatedVcdC *tfp;
private:
  FILE *vcdfile = nullptr;
#endif
};
#endif

// For option parsing, which is split across this file, Verilog, and
// FESVR's HTIF, a few external files must be pulled in. The list of
// files and what they provide is enumerated:
//
// $RISCV/include/fesvr/htif.h:
//   defines:
//     - HTIF_USAGE_OPTIONS
//     - HTIF_LONG_OPTIONS_OPTIND
//     - HTIF_LONG_OPTIONS
// $(ROCKETCHIP_DIR)/generated-src(-debug)?/$(CONFIG).plusArgs:
//   defines:
//     - PLUSARG_USAGE_OPTIONS
//   variables:
//     - static const char * verilog_plusargs

extern dtm_t *dtm;
extern remote_bitbang_t *jtag;

static uint64_t trace_count = 0;

bool verbose;
bool done_reset;

void handle_sigterm(int sig) {
  dtm->stop();
}

double sc_time_stamp() {
  return trace_count;
}

extern "C" int vpi_get_vlog_info(void *arg) {
  return 0;
}

static void usage(const char *program_name) {
  printf("Usage: %s [EMULATOR OPTION]... [VERILOG PLUSARG]... [HOST OPTION]... BINARY [TARGET OPTION]...\n",
         program_name);
  fputs("\
Run a BINARY on the Rocket Chip emulator.\n\
\n\
Mandatory arguments to long options are mandatory for short options too.\n\
\n\
EMULATOR OPTIONS\n\
  -c, --cycle-count        Print the cycle count before exiting\n\
       +cycle-count\n\
  -h, --help               Display this help and exit\n\
  -m, --max-cycles=CYCLES  Kill the emulation after CYCLES\n\
       +max-cycles=CYCLES\n\
  -s, --seed=SEED          Use random number seed SEED\n\
  -r, --rbb-port=PORT      Use PORT for remote bit bang (with OpenOCD and GDB) \n\
                           If not specified, a random port will be chosen\n\
                           automatically.\n\
  -V, --verbose            Enable all Chisel printfs (cycle-by-cycle info)\n\
       +verbose\n\
", stdout);
#if VM_TRACE == 0
  fputs("\
\n\
EMULATOR DEBUG OPTIONS (only supported in debug build -- try `make debug`)\n",
        stdout);
#endif
  fputs("\
  -v, --vcd=FILE,          Write vcd trace to FILE (or '-' for stdout)\n\
  -x, --dump-start=CYCLE   Start VCD tracing at CYCLE\n\
       +dump-start\n\
", stdout);
  fputs("\n" PLUSARG_USAGE_OPTIONS, stdout);
  fputs("\n" HTIF_USAGE_OPTIONS, stdout);
  printf("\n"
         "EXAMPLES\n"
         "  - run a bare metal test:\n"
         "    %s $RISCV/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-add\n"
         "  - run a bare metal test showing cycle-by-cycle information:\n"
         "    %s +verbose $RISCV/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-add 2>&1 | spike-dasm\n"
         #if VM_TRACE
         "  - run a bare metal test to generate a VCD waveform:\n"
         "    %s -v rv64ui-p-add.vcd $RISCV/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-add\n"
         #endif
         "  - run an ELF (you wrote, called 'hello') using the proxy kernel:\n"
         "    %s pk hello\n",
         program_name, program_name, program_name
#if VM_TRACE
      , program_name
#endif
  );
}

int main(int argc, char **argv) {
  unsigned random_seed = (unsigned) time(NULL) ^ (unsigned) getpid();
  uint64_t max_cycles = -1;
  int ret = 0;
  bool print_cycles = false;
  // Port numbers are 16 bit unsigned integers. 
  uint16_t rbb_port = 0;
#if VM_TRACE
  uint64_t start = 0;
  VerilatedTrace trace;
#endif
  char **htif_argv = NULL;
  int verilog_plusargs_legal = 1;

  while (1) {
    static struct option long_options[] = {
        {"cycle-count", no_argument, 0, 'c'},
        {"help", no_argument, 0, 'h'},
        {"max-cycles", required_argument, 0, 'm'},
        {"seed", required_argument, 0, 's'},
        {"rbb-port", required_argument, 0, 'r'},
        {"verbose", no_argument, 0, 'V'},
#if VM_TRACE
        {"vcd", required_argument, 0, 'v'},
        {"dump-start", required_argument, 0, 'x'},
#endif
        HTIF_LONG_OPTIONS
    };
    int option_index = 0;
#if VM_TRACE
    int c = getopt_long(argc, argv, "-chm:s:r:v:Vx:", long_options, &option_index);
#else
    int c = getopt_long(argc, argv, "-chm:s:r:V", long_options, &option_index);
#endif
    if (c == -1) break;
    retry:
    switch (c) {
      // Process long and short EMULATOR options
      case '?':
        usage(argv[0]);
        return 1;
      case 'c':
        print_cycles = true;
        break;
      case 'h':
        usage(argv[0]);
        return 0;
      case 'm':
        max_cycles = atoll(optarg);
        break;
      case 's':
        random_seed = atoi(optarg);
        break;
      case 'r':
        rbb_port = atoi(optarg);
        break;
      case 'V':
        verbose = true;
        break;
#if VM_TRACE
      case 'v': {
        trace.activate(optarg);
        break;
      }
      case 'x':
        start = atoll(optarg);
        break;
#endif
        // Process legacy '+' EMULATOR arguments by replacing them with
        // their getopt equivalents
      case 1: {
        std::string arg = optarg;
        if (arg.substr(0, 1) != "+") {
          optind--;
          goto done_processing;
        }
        if (arg == "+verbose")
          c = 'V';
        else if (arg.substr(0, 12) == "+max-cycles=") {
          c = 'm';
          optarg = optarg + 12;
        }
#if VM_TRACE
        else if (arg.substr(0, 12) == "+dump-start=") {
          c = 'x';
          optarg = optarg + 12;
        }
#endif
        else if (arg.substr(0, 12) == "+cycle-count")
          c = 'c';
          // If we don't find a legacy '+' EMULATOR argument, it still could be
          // a VERILOG_PLUSARG and not an error.
        else if (verilog_plusargs_legal) {
          const char **plusarg = &verilog_plusargs[0];
          int legal_verilog_plusarg = 0;
          while (*plusarg && (legal_verilog_plusarg == 0)) {
            if (arg.substr(1, strlen(*plusarg)) == *plusarg) {
              legal_verilog_plusarg = 1;
            }
            plusarg++;
          }
          if (!legal_verilog_plusarg) {
            verilog_plusargs_legal = 0;
          } else {
            c = 'P';
          }
          goto retry;
        }
          // If we STILL don't find a legacy '+' argument, it still could be
          // an HTIF (HOST) argument and not an error. If this is the case, then
          // we're done processing EMULATOR and VERILOG arguments.
        else {
          static struct option htif_long_options[] = {HTIF_LONG_OPTIONS};
          struct option *htif_option = &htif_long_options[0];
          while (htif_option->name) {
            if (arg.substr(1, strlen(htif_option->name)) == htif_option->name) {
              optind--;
              goto done_processing;
            }
            htif_option++;
          }
          std::cerr << argv[0] << ": invalid plus-arg (Verilog or HTIF) \""
                    << arg << "\"\n";
          c = '?';
        }
        goto retry;
      }
      case 'P':
        break; // Nothing to do here, Verilog PlusArg
        // Realize that we've hit HTIF (HOST) arguments or error out
      default:
        if (c >= HTIF_LONG_OPTIONS_OPTIND) {
          optind--;
          goto done_processing;
        }
        c = '?';
        goto retry;
    }
  }

  done_processing:
  if (optind == argc) {
    std::cerr << "No binary specified for emulator\n";
    usage(argv[0]);
    return 1;
  }
  int htif_argc = 1 + argc - optind;
  htif_argv = (char **) malloc((htif_argc) * sizeof(char *));
  if(!htif_argv){
    std::cerr << "malloc for htif_argv failed!" << std::endl;
    return 1;
  }
  htif_argv[0] = argv[0];
  for (int i = 1; optind < argc;) htif_argv[i++] = argv[optind++];

  if (verbose)
    fprintf(stderr, "using random seed %u\n", random_seed);

  srand(random_seed);
  srand48(random_seed);

  Verilated::randReset(2);
  Verilated::commandArgs(argc, argv);
  TEST_HARNESS *tile = new TEST_HARNESS;

#if VM_TRACE
  trace.setTrace(tile);
#endif

  jtag = new remote_bitbang_t(rbb_port);
  dtm = new dtm_t(htif_argc, htif_argv);

  signal(SIGTERM, handle_sigterm);

  bool dump;
  // reset for several cycles to handle pipelined reset
  for (int i = 0; i < 10; i++) {
    tile->reset = 1;
    tile->clock = 0;
    tile->eval();
#if VM_TRACE
    dump = trace.tfp && trace_count >= start;
    if (dump)
      trace.dump(static_cast<vluint64_t>(trace_count * 2));
#endif
    tile->clock = 1;
    tile->eval();
#if VM_TRACE
    if (dump)
      trace.dump(trace_count * 2 + 1);
#endif
    trace_count++;
  }
  tile->reset = 0;
  done_reset = true;

  while (!dtm->done() && !jtag->done() &&
         !tile->io_success && trace_count < max_cycles) {
    tile->clock = 0;
    tile->eval();
#if VM_TRACE
    dump = trace.tfp && trace_count >= start;
    if (dump)
      trace.dump(trace_count * 2);
#endif

    tile->clock = 1;
    tile->eval();
#if VM_TRACE
    if (dump)
      trace.dump(trace_count * 2 + 1);
#endif
    trace_count++;
  }

  if (dtm->exit_code()) {
    fprintf(stderr, "*** FAILED *** via dtm (code = %d, seed %d) after %llu cycles\n", dtm->exit_code(), random_seed,
            trace_count);
    ret = dtm->exit_code();
  } else if (jtag->exit_code()) {
    fprintf(stderr, "*** FAILED *** via jtag (code = %d, seed %d) after %llu cycles\n", jtag->exit_code(), random_seed,
            trace_count);
    ret = jtag->exit_code();
  } else if (trace_count == max_cycles) {
    fprintf(stderr, "*** FAILED *** via trace_count (timeout, seed %d) after %llu cycles\n", random_seed, trace_count);
    ret = 2;
  } else if (verbose || print_cycles) {
    fprintf(stderr, "*** PASSED *** Completed after %llu cycles\n", trace_count);
  }

  delete dtm;
  delete jtag;
  delete tile;

  free(htif_argv);

  return ret;
}
