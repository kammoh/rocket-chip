package sha3

import chisel3._
import chisel3.rotate._

/**
  * FIPS 202 Keccak-f[1600] block permutation transforms
  * see: https://en.wikipedia.org/wiki/SHA-3
  */
object Transforms {
  type StateData = Vec[UInt]

  implicit class StateVec(v: StateData) {
    require(v.length == KeccakState.NUM_LANES)

    def lane(i: Int, j: Int): UInt = v((i % 5) * 5 + (j % 5))

    private def linearToRowCol(idx: Int) = (idx / 5, idx % 5)

    def rowColIndices = v.indices.map(i => linearToRowCol(i))

    def zipWithRowCol = v.zipWithIndex.map(tup => (tup._1, linearToRowCol(tup._2)))

    def rows =
      v.zipWithIndex.groupBy(tup => tup._2 % 5).toSeq.map(_._2.map(_._1))
  }

  /**
    * Compute the parity of each of the columns, and xor that into two nearby columns
    * a[i][ j][k] ← a[i][ j][k] ⊕ parity(a[0...4][ j−1][k]) ⊕ parity(a[0...4][ j+1][k−1])
    * @param in current state
    * @return Theta transform
    */
  def theta(in: StateData): StateData = {
    val parity = in.rows.map(g => g.reduce(_ ^ _))
    VecInit(
      in.zipWithRowCol.map {
        case (lane, (_, col)) => lane ^ parity((col + 4) % 5) ^ parity((col + 1) % 5).rotateLeft(1)
      })
  }

  /**
    * Bitwise rotate each of the 25 words by a different triangular number
    * Constants are computing in the code but are static
    * @param in current state
    * @return \rho transformed state
    */
  def rho(in: StateData): StateData = {
    // (i, j)[n] = {(3,1), (2, 0)}^n * (0, 1) = {(3,1), (2, 0)} * (i,j)[n-1] = ((2i + 3j) mod 5, i)
    def tupStream(s: (Int, Int) = (0, 0)): Stream[(Int, Int)] =
      Stream.cons(s, tupStream(s match {
        case tup if tup == (0, 0) => (0, 1)
        case (i, j) => ((3 * i + 2 * j) % 5, i)
      }))

    val rhoConsts = tupStream().zipWithIndex.map {
      // a[i][j][k] <- a[i][j][k−(t+1)(t+2)/2]
      case ((i, j), t) => (5 * i + j) -> ((t * (t + 1)) / 2) % 64
    }.take(KeccakState.NUM_LANES).sorted.map(_._2)

    VecInit(in.zip(rhoConsts).map { case (lane, rho) => lane rotateLeft rho })
  }

  /**
    * Permute the 25 words in a fixed pattern. a[j][2i+3j] ← a[ i][j].
    * @param in current state
    * @return \pi transformed state
    */
  def pi(in: StateData): StateData =
    VecInit(in.rowColIndices.map { case (row, col) => in.lane(col, 3 * row + col) })

  /**
    * Bitwise combine along rows, using x ← x ⊕ (¬y & z)
    * a[i][ j][k] ← a[i][ j][k] ⊕ (¬a[i][ j+1][k] & a[i][ j+2][k])
    * @param in current state
    * @return \chi transformed state
    */
  def chi(in: StateData): StateData = VecInit(
    in.rowColIndices.map {
      case (row, col) =>
        in.lane(row, col) ^ (~in.lane(row, (col + 1) % 5) & in.lane(row, (col + 2) % 5))
    })

  val NUM_ROUNDS = 24

  /**
    * Exclusive-or a round constant into one word of the state.
    * in round n, for 0 ≤ m ≤ ℓ, a[0][0][2m−1] is XORed with bit m + 7n of a degree-8 LFSR sequence.
    * Constants are computing in the code but are static
    * @param round round value [0..[[NUM_ROUNDS]] - 1]
    * @param in current state
    * @return \iota transformed state
    */
  def iota(round: UInt)(in: StateData): StateData = {
    def lfsrStream(lfsr: Int = 1): Stream[Int] =
      Stream.cons(lfsr, lfsrStream((lfsr << 1) ^ (if ((lfsr & 0x80) == 0) 0 else 0x71)))

    val rc = VecInit(
      lfsrStream().grouped(7).map(
        _.zipWithIndex.map { case (d, i) => BigInt(d & 1) << ((1 << i) - 1) }.sum.U
      ).take(NUM_ROUNDS).toSeq)

    VecInit((in.head ^ rc(round)) +: in.tail)
  }

  /**
    * Complete block permutation function
    * @param round round value [0..[[NUM_ROUNDS]] - 1]
    * @param in current state
    * @return permuted state
    */
  def keccakPermutation(round: UInt)(in: StateData): StateData =
    iota(round)(chi(pi(rho(theta(in)))))
}
