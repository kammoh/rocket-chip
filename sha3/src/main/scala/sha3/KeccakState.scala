package sha3

import chisel3._
import chisel3.experimental.chiselName

@chiselName
class KeccakState {

  import Transforms.StateData

  val ksVec = Reg(Vec(25, UInt(64.W)))

  def update(f: StateData => StateData): Unit = ksVec := f(ksVec)

  def initialize(): Unit = update(s => VecInit(Seq.fill(s.length)(0.U)))

  def updateLane(index: UInt, f: UInt => UInt): Unit = ksVec(index) := f(ksVec(index))

  def xorLane(index: UInt, inLane: UInt): Unit = updateLane(index, w => w ^ inLane)

  def xorByte(byteIndex: UInt, byte: UInt): Unit = xorLane(byteIndex >> 3, byte << byteIndex(0, 2))

}

