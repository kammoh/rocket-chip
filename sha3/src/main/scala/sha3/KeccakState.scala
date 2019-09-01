package sha3

import chisel3._
import chisel3.experimental.chiselName
import sha3.Transforms.keccakPermutation

@chiselName
class KeccakState {

  import Transforms.StateData
  import KeccakState.NUM_LANES

  val ksVec = Reg(Vec(NUM_LANES, UInt(64.W)))

  def apply(i: Int) = ksVec(i)

  def apply(i: UInt) = ksVec(i)

  def update(f: StateData => StateData): Unit = ksVec := f(ksVec)

  def initialize(): Unit = update(s => VecInit(Seq.fill(s.length)(0.U)))

  def updateLane(index: UInt, f: UInt => UInt): Unit = ksVec(index) := f(ksVec(index))

  def xorLane(index: UInt, in: UInt): Unit =
    updateLane(index, w => w ^ in)

  //  def xorLane(index: UInt, in: UInt, partial: Bool, partialBytes: UInt): Unit =
  //    updateLane(index, w =>
  //      w ^ (UIntToOH(partialBytes, 8) - 1.U) (7, 0).asBools.reverse.zipWithIndex.map {
  //        case (b, i) => Fill(8, b | !partial) & in(8 * i + 7, 8 * i)
  //      }.reduce(_ ## _)
  //    )

  //  def xorByte(byteIndex: UInt, byte: UInt): Unit = xorLane(byteIndex >> 3, byte << byteIndex(0, 2))

  def dump(): Unit = {
    printf(
      ksVec.zipWithIndex.map { case (lane, idx) =>
        p"${idx}: ${Hexadecimal(ksVec(idx))}     "
      }.reduce(_ + _) + "\n"
    )
  }

  def permutate(round: UInt): Unit = update(keccakPermutation(round))
}

object KeccakState {
  val NUM_LANES = 25
}