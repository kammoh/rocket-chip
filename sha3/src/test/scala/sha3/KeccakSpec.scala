package sha3

import chisel3._
import chisel3.experimental.ChiselEnum
import chisel3.tester._
import chisel3.tester.experimental.TestOptionBuilder._
import chisel3.tester.internal.{VerilatorBackendAnnotation, WriteVcdAnnotation}
import chisel3.util._
import org.scalatest._
import org.scalatest.prop._
import sha3.Transforms.keccakPermutation
import xisel.viva.SynthSpec
//import org.bouncycastle.crypto.digests.KeccakDigest

class KeccakTestModule extends Module {
  val io = IO(new Bundle {
    val in = Input(Valid(UInt(64.W)))
    val laneIdx = Input(UInt(log2Ceil(25).W))
    val rateLanes = Input(UInt(log2Ceil(25).W))
    val out = Output(UInt(64.W))
  })
  val ks = new KeccakState

  object State extends ChiselEnum {
    val idle, absorb, permute, squeeze = Value
  }

  val state = RegInit(State.idle)
  val roundCtr = Reg(UInt(log2Ceil(Transforms.NUM_ROUNDS).W))

  val rateLanes = Reg(UInt())

  switch(state) {
    is(State.idle) {
      ks.initialize()
      roundCtr := 0.U
      state := State.absorb
      rateLanes := io.rateLanes
    }
    is(State.absorb) {
      when(io.in.fire()) {
        //        printf(p"xoring lane ${io.laneIdx} (${Hexadecimal(ks(io.laneIdx))}) with ${Hexadecimal(io.in.bits)}\n")
        ks.xorLane(io.laneIdx, io.in.bits)
        roundCtr := roundCtr + 1.U
        when(roundCtr === rateLanes - 1.U) {
          roundCtr := 0.U
          state := State.permute
        }
      }
    }
    is(State.permute) {
      printf(p"roundCtr=${roundCtr}\n")
      ks.dump()
      ks.update(keccakPermutation(roundCtr))
      roundCtr := roundCtr + 1.U
      when(roundCtr === (Transforms.NUM_ROUNDS - 1).U) {
        roundCtr := 0.U
        state := State.squeeze
      }
    }
    is(State.squeeze) {
      when(roundCtr === 0.U) {
        ks.dump()
      }
      roundCtr := roundCtr + 1.U
      when(roundCtr === rateLanes - 1.U) {
        roundCtr := 0.U
        state := State.idle
      }
    }
  }

  io.out := ks(io.laneIdx)
}

class KeccakSpec extends FlatSpec with GeneratorDrivenPropertyChecks with ChiselScalatestTester {
  behavior of "Keccak"

  //  val annos = Seq(WriteVcdAnnotation)
  //  val annos = Seq(WriteVcdAnnotation, VerilatorBackendAnnotation)
  val annos = Seq()
  it should "xor" in {
    test(new KeccakTestModule).withAnnotations(annos) { c =>

      val golden = new KeccakDigest(288) {
        reset()

        def absorb(data: Seq[Byte]): Unit =
          super.absorb(data.toArray, 0, data.length)

        def squeeze(length: Int) = {
          val output = Array.ofDim[Byte](length)
          super.justSqueeze(output, 0, length * 8)
          output
        }
      }

      val rateLanes = golden.getByteLength / 8

      c.io.in.valid.poke(0.B)
      c.io.rateLanes.poke(rateLanes.U)
      c.clock.step(5)

      val rnd = scala.util.Random
      //      val in = (0 until rateLanes).flatMap(i => (i +: Seq.fill(7)(0)).map(_.toByte)).toArray
      val in = Array.ofDim[Byte](8 * rateLanes)
      rnd.nextBytes(in)
      val inU64 = in.grouped(8).map(l => BigInt(0.toByte +: l.reverse).U).toSeq

      for (i <- 0 until rateLanes) {
        c.io.in.valid.poke(1.B)
        c.io.in.bits.poke(inU64(i))
        c.io.laneIdx.poke(i.U)
        c.clock.step()
        c.io.in.valid.poke(0.B)
      }


      golden.absorb(in)

      val exp = golden.squeeze(in.length).grouped(8).map(l => BigInt(0.toByte +: l.reverse).U).toSeq

      c.clock.step(Transforms.NUM_ROUNDS)

      for (i <- 0 until rateLanes) {
        c.io.laneIdx.poke(i.U)
        c.io.out.expect(exp(i))
        c.clock.step()
      }

    }
  }
}

class KeccakTestFpgaSynth extends SynthSpec {
  behavior of "Vivado Synthesis"

  it should "synthesize" in {
    synth(() => new KeccakTestModule, Map(
      "common" ->
        Map(
//          "part" -> "xc7a100tcsg324-1",
          "part" -> "xc7a35ticsg324-1L",
          "target_frequency" -> 200.000
        )
    ))
  }
}
