

## Comparison to other implementations of Keccak

|  Resource   | This design | VHDL      |
|-------------|-------------|-----------|
| Slice       |   **876**   | 922       |
| LUT         |   **3331**  | 3365      |
| FF          |   **1615**  | 2707      |
| F7/F8 Mux   |   128/64    | 0/0       |
| LoC[^2]     |   **177**   | 431       |

Table 1: Comparison to KeccakVHDL v3.1 high_speed_core  (https://keccak.team/hardware.html)

[^1]: FPGA Synthesis for `7a100tcsg324-1` Vivado v.2018.3 with 200 MHz clock frequency constrain, area optimized settings

[^2]: LoC(Lines of Code): Lines of source code (excluding empty lines, comments, etc) including test-benches as reported by `tokei`

Chisel: 
VHDL