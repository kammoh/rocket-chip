import chisel3._
import chisel3.util._
import scala.language.implicitConversions

package object sha3 {

  implicit class fromSeqToVec[T <: Data](seq: Seq[T]) {
    def toVec = VecInit(seq)
  }

  implicit class RotatableUInt(u: UInt) {
    def <<<(x: Int): UInt =
      if (x == 0)
        u
      else if (x > 0) {
        val w = u.getWidth
        u(w - x - 1, 0) ## u(w - 1, w - x)
      }
      else
        this >>> (-x)

    def >>>(x: Int): UInt =
      if (x > 0) {
        val w = u.getWidth
        u(x - 1, 0) ## u(w - 1, x)
      }
      else
        this <<< (-x)
  }


  class Counter(n: Int) extends chisel3.util.Counter(n) {
    def reset() = value := 0.U
  }

  object Counter {
    def apply(n: Int) = new Counter(n)
  }

  implicit def saneCounterToUInt(c: Counter): UInt = c.value

  implicit def bitsToUInt(b: Bits): UInt = b.asUInt
}
