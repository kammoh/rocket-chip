package sha3

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselEnum, chiselName}
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.tile.{HasCoreParameters, LazyRoCC, LazyRoCCModuleImp, OpcodeSet}
import freechips.rocketchip.rocket._
import Transforms.NUM_ROUNDS
import KeccakState.NUM_LANES

/**
 * FIPS 202 SHA3/SHAKE KECCAK-f[1600]
 *
 * @note srcAddr: address of the first byte of a byte aligned message
 * @param opcodes only a single custom opcode is currently supported
 * @param p
 *
 *
 *                | instruction  | func3 |  rs1  | rs2  |  rd  |
 *                |--------------|-------|-------|------|------|
 *                | sha3_init    |  0    | rate  | sep  | stat |
 *                | sha3_absorb  |  1    | addr  | size | done |
 *                | sha3_squeeze |  2    | addr  | size | done |
 *
 *                size: in bytes but should be multiple of xBytes, in SV39 it's 36 bits of data
 *                size: in bytes but should be multiple of xBytes, in SV39 it's 36 bits of data
 *
 *                * All addresses and sizes need to be word aligned
 *
 */
class Sha3Accelerator(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes, 0) {
  override lazy val module = new Sha3Imp(this)
}


@chiselName
class Sha3Imp(outer: Sha3Accelerator)(implicit p: Parameters) extends LazyRoCCModuleImp(outer)
  with HasCoreParameters {
  val cacheParams = tileParams.dcache.get

  val KECCAKSTATE_WORDS = NUM_LANES
  val rateWords = RegInit(0.U(log2Ceil(KECCAKSTATE_WORDS).W)) // rate / 64

  val round = Reg(UInt(log2Ceil(NUM_ROUNDS).W))
  val reqWords = Reg(UInt(log2Ceil(KECCAKSTATE_WORDS).W))
  val pendingWords = Reg(UInt((coreMaxAddrBits - log2Ceil(xBytes)).W))
  val addrWord = Reg(UInt((coreMaxAddrBits - log2Ceil(xBytes)).W))
  val inFlightWords = Reg(UInt(log2Ceil(KECCAKSTATE_WORDS).W))
  val rd = Reg(UInt())
  val xd = Reg(Bool())

  val ks = new KeccakState

  object FsmState extends ChiselEnum {
    val idle, absorbXor, absorbPteReq, absorbPermutate, padAndFinalize, squeezePermutate, squeezeStore, wbRd = Value
  }

  val state = RegInit(FsmState.idle)

  object RdStatus extends ChiselEnum {
    val ok, parameterError, alignmentError = Value
  }

  val rdStatus = RegInit(RdStatus.ok)
  val haveBeenSqueezing = Reg(Bool())

  object Funct extends ChiselEnum {
    val init, absorb, squeeze = Value
  }

  //  val cmd = Queue(io.cmd, entries = 2)
  val funct = Funct(io.cmd.bits.inst.funct(Funct.getWidth - 1, 0))
//  private val ptw = io.ptw(0)

  io.mem.req.bits.addr := addrWord << log2Ceil(xBytes)
  io.mem.req.bits.tag := reqWords
  io.mem.req.bits.phys := false.B
  io.mem.req.bits.signed := false.B
  io.mem.req.bits.data := ks(reqWords)
  io.mem.req.bits.cmd := M_XRD
  io.mem.req.bits.size := log2Ceil(coreDataBytes).U // 64-bit chunks for RV64 or RV32D ?
  io.mem.req.valid := false.B
  io.resp.valid := false.B
  io.resp.bits.rd := rd
  io.resp.bits.data := rdStatus.asUInt
  io.busy := true.B
  io.interrupt := false.B

  val permutateDone = round === (Transforms.NUM_ROUNDS - 1).U
  val permutate = WireDefault(false.B)

  val rateFull = reqWords === rateWords

  var arg1w = io.cmd.bits.rs1 >> log2Ceil(xBytes)
  var arg1_is_word_alligned = io.cmd.bits.rs1(log2Ceil(xBytes) - 1, 0) === 0.U
  var arg2w = io.cmd.bits.rs2 >> log2Ceil(xBytes)
  var arg2_is_word_alligned = io.cmd.bits.rs2(log2Ceil(xBytes) - 1, 0) === 0.U

  switch(state) {
    is(FsmState.idle) {
      io.busy := false.B
      io.cmd.ready := true.B

      when(io.cmd.fire()) {
        xd := io.cmd.bits.inst.xd
        rd := io.cmd.bits.inst.rd
        switch(funct) {
          is(Funct.init) {
            ks.initialize()
            rateWords := arg1w
            haveBeenSqueezing := false.B
          }
          is(Funct.absorb) {
            reqWords := 0.U
            inFlightWords := 0.U
            when(rateFull) {
              state := FsmState.absorbPermutate
            }.otherwise {
              state := FsmState.absorbXor
            }
            haveBeenSqueezing := false.B
          }
          is(Funct.squeeze) {
            when(haveBeenSqueezing) {
              state := FsmState.squeezePermutate
            }.otherwise {
              state := FsmState.padAndFinalize
            }
          }
        }

        when(funct === Funct.absorb || funct === Funct.squeeze) {
          addrWord := arg1w
          pendingWords := arg2w
          when(!arg1_is_word_alligned || !arg2_is_word_alligned) {
            //            io.interrupt := true.B
            rdStatus := RdStatus.alignmentError
            state := FsmState.wbRd
          }
        }
      }
    }
    is(FsmState.absorbXor) {
      io.mem.req.valid := !rateFull && pendingWords =/= 0.U
      when(io.mem.req.fire()) {
        reqWords := reqWords + 1.U
        pendingWords := pendingWords - 1.U
        addrWord := addrWord + 1.U
        when(!io.mem.resp.fire()) {
          inFlightWords := inFlightWords + 1.U
        }
      }
      when(io.mem.resp.fire()) {
        ks.xorLane(io.mem.resp.bits.tag, io.mem.resp.bits.data)
        when(!io.mem.req.valid && inFlightWords === 1.U) { // last response
          reqWords := 0.U
          when(pendingWords =/= 0.U) { // --> rateFull === true.B <--> wordsRequested === rateWords
            // still words left so we'll come back
            state := FsmState.absorbPermutate
          }.otherwise {
            state := FsmState.wbRd
          }
        }
        when(!io.mem.req.fire()) { // invariant: inFlightWords > 0
          inFlightWords := inFlightWords - 1.U
        }
      }
    }
    is(FsmState.absorbPermutate) {
      permutate := true.B
      when(permutateDone) {
        state := FsmState.absorbXor
      }
    }
    is(FsmState.padAndFinalize) {
      haveBeenSqueezing := true.B
      ks.xorLane(rateWords - 1.U, (BigInt(1) << 63).U)
      state := FsmState.squeezePermutate
    }
    is(FsmState.squeezePermutate) {
      permutate := true.B
      when(permutateDone) {
        reqWords := 0.U
        state := FsmState.squeezeStore
      }
    }
    is(FsmState.squeezeStore) {
      io.mem.req.bits.cmd := M_XWR
      io.mem.req.valid := !rateFull

      when(io.mem.req.fire()) {
        reqWords := reqWords + 1.U
        pendingWords := pendingWords - 1.U
      }
      when(rateFull) {
        when(pendingWords =/= 0.U) {
          state := FsmState.squeezePermutate
        }.otherwise {
          state := FsmState.wbRd
        }
      }
    }
    is(FsmState.wbRd) {
      io.resp.valid := xd
      when(!xd || io.resp.fire()) {
        state := FsmState.idle
      }
    }
  }

  when(permutate) {
    ks.permutate(round)
    round := round + 1.U
    when(permutateDone) {
      round := 0.U
    }
  }
}
