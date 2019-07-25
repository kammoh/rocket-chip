package sha3

import chisel3._

object Transforms {
  type StateData = Vec[UInt]

  implicit class StateVec(v: StateData) {
    def lane(i: Int, j: Int): UInt = v((i % 5) * 5 + (j % 5))

    def linearToRowCol(idx: Int) = (idx / 5, idx % 5)

    def rowColIndices = v.indices.map(i => linearToRowCol(i))

    def zipWithRowCol = v.zipWithIndex.map(tup => (tup._1, linearToRowCol(tup._2)))
  }

  def theta(in: StateData): StateData = {
    val parity = in.reduce(_ ^ _)
    in.zipWithRowCol.map {
      case (lane, (_, col)) => lane ^ parity((col + 4) % 5) ^ parity((col + 1) % 5) <<< 1
    }.toVec
  }

  def rho(in: StateData): StateData = {
    val myRhoConsts = Seq(0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)
    in.zipWithIndex.map { case (lane, idx) => lane <<< myRhoConsts(idx) }.toVec
  }

  def pi(in: StateData): StateData = {
    in.rowColIndices.map { case (row, col) => in.lane(col, 3 * row + col) }.toVec
  }

  def chi(in: StateData): StateData = {
    in.rowColIndices.map {
      case (row, col) =>
        in.lane(row, col) ^ (~in.lane(row, (col + 1) % 5) & in.lane(row, (col + 2) % 5))
    }.toVec
  }


  def iota(round: UInt)(in: StateData): StateData = {
    val RC = VecInit(
      "h0000000000000000".U, "h0000000000000001".U, "h0000000000008082".U, "h800000000000808a".U, "h8000000080008000".U,
      "h000000000000808b".U, "h0000000080000001".U, "h8000000080008081".U, "h8000000000008009".U, "h000000000000008a".U,
      "h0000000000000088".U, "h0000000080008009".U, "h000000008000000a".U, "h000000008000808b".U, "h800000000000008b".U,
      "h8000000000008089".U, "h8000000000008003".U, "h8000000000008002".U, "h8000000000000080".U, "h000000000000800a".U,
      "h800000008000000a".U, "h8000000080008081".U, "h8000000000008080".U, "h0000000080000001".U, "h8000000080008008".U
    )
    ((in.head ^ RC(round)) +: in.tail).toVec
  }

  val NUM_ROUNDS = 24

  def sliceProc(round: UInt)(in: StateData): StateData = {
    Mux(round === NUM_ROUNDS.U, in, Mux(round === 0.U, in, iota(round)(chi(pi(in)))))
  }

  def keccakPermutation(round: UInt)(in: StateData): StateData = {
    val common = sliceProc(round)(in)
    Mux(round === NUM_ROUNDS.U, common, rho(common))
  }
}
