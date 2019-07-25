package sha3

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselEnum, chiselName}
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.tile.{HasCoreParameters, LazyRoCC, LazyRoCCModuleImp, OpcodeSet}
import freechips.rocketchip.rocket._


/**
  * FIPS 202 SHA3/SHAKE KECCAK-f[1600]
  *
  * @note srcAddr: address of the first byte of a byte aligned message
  * @param opcodes
  * @param p
  *
  *
  * | instruction  | func3 |  rs1    | rs2  |  rd  |
  * |--------------|-------|---------|------|------|
  * | sha3_init    |  0    | rate    | sep  |   -  |
  * | sha3_absorb  |  1    | srcAddr | size | done |
  * | sha3_squeeze |  2    | dstAddr | size | done |
  *
  */
class Sha3Accelerator(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes, 0) {
  override lazy val module = new Sha3Imp(this)
}

@chiselName
class Sha3Imp(outer: Sha3Accelerator)(implicit p: Parameters) extends LazyRoCCModuleImp(outer) with HasCoreParameters {

  val rate = Reg(UInt(log2Ceil(25).W)) // rate / 64
  val sep = Reg(UInt(8.W)) // domain separator byte (little-endian)

  val bytesPending = Reg(UInt())

  val ks = new KeccakState

  object FsmState extends ChiselEnum {
    val idle, initialized, absorbLoadXor, absorbRound, absorbDone, squeezeRound, squeezeStore, squeezeDone = Value
  }

  object Funct extends ChiselEnum {
//    val init, absorb, squeeze = Value
    val init :: absorb :: squeeze :: Nil = Enum(3)
  }

  val state = RegInit(FsmState.idle)

  import Transforms.{NUM_ROUNDS, keccakPermutation}

  val round = Counter(NUM_ROUNDS)

  val cmd = Queue(io.cmd, entries = 2)

  cmd.ready := true.B // FIXME
  //  (is_transfer && clientArb.io.in(0).req.ready) ||
  //               (is_sg && sgunit.io.cpu.req.ready) ||
  //               is_cr_write || // Write can always go through immediately
  //               (is_cr_read && io.resp.ready)

  val reqCntr = Counter(25)
  val respCntr = Counter(25)

  val funct = cmd.bits.inst.funct


  io.mem.req.valid := false.B

  switch(state) {
    is(FsmState.idle) {
      when(cmd.fire() && funct === Funct.init.asUInt) {
        rate := cmd.bits.rs1
        sep := cmd.bits.rs2
        ks.initialize()

        state := FsmState.initialized
      }
    }
    is(FsmState.initialized) {
      when(cmd.fire() && funct === Funct.absorb.asUInt) {

        respCntr.reset()
        state := FsmState.absorbLoadXor
      }
    }
    is(FsmState.absorbLoadXor) {

      io.mem.req.valid := true.B

      when(reqCntr === rate || reqCntr === bytesPending) {
        when(respCntr === rate) {

          state := FsmState.absorbRound
        }
      }
    }

    is(FsmState.absorbDone) {
      when(cmd.fire()) {

      }

      switch(funct) {
        is(Funct.absorb.asUInt) {
          bytesPending := cmd.bits.rs2
        }
        is(Funct.squeeze.asUInt) {
          bytesPending := cmd.bits.rs2
        }
      }
    }
  }


  round.reset()


  when(state === FsmState.absorbRound || state === FsmState.squeezeRound) {
    ks.update(keccakPermutation(round))
    round.inc()

    when(round === NUM_ROUNDS.U) {
      when(bytesPending === 0.U) {
        state := FsmState.absorbRound
      }.otherwise {
        state := FsmState.absorbDone
      }
    }
  }


  val baseAddress = Reg(UInt(xLen.W))


//  when(cmd.fire() && ???) {
//    baseAddress := cmd.bits.rs1
//  }


  val memReqAddr = reqCntr + baseAddress

  val memRespTag = io.mem.resp.bits.tag(log2Ceil(25) - 1, 0)


//  when(cmd.fire() && ???) {
//  }

  when(io.mem.resp.valid) {
  }

  // control
  when(io.mem.req.fire()) {
  }

  //  val doResp = cmd.bits.inst.xd
  //  val stallReg = busy(addr)
  //  val stallLoad = doLoad && !io.mem.req.ready
  //  val stallResp = doResp && !io.resp.ready
  //
  //  cmd.ready := !stallReg && !stallLoad && !stallResp
  // command resolved if no stalls AND not issuing a load that will need a request

  // PROC RESPONSE INTERFACE
  io.resp.valid := false.B // cmd.valid && doResp && !stallReg && !stallLoad
  // valid response if valid command, need a response, and no stalls
  io.resp.bits.rd := cmd.bits.inst.rd
  // Must respond with the appropriate tag or undefined behavior
  io.resp.bits.data := 1.U
  // Semantics is to always send out prior accumulator register value

  io.busy := cmd.valid || true.B
  // Be busy when have pending memory requests or committed possibility of pending requests
  io.interrupt := false.B
  // Set this true to trigger an interrupt on the processor (please refer to supervisor documentation)

  // MEMORY REQUEST INTERFACE
  //  io.mem.req.valid := cmd.valid && doLoad && !stallReg && !stallResp
  io.mem.req.bits.addr := 0.U
  io.mem.req.bits.tag := memReqAddr
  io.mem.req.bits.cmd := M_XRD // perform a load (M_XWR for stores)
  io.mem.req.bits.size := log2Ceil(8).U
  io.mem.req.bits.signed := false.B
  io.mem.req.bits.data := 0.U // we're not performing any stores...
  io.mem.req.bits.phys := false.B
}
