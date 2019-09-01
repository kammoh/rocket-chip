import Chisel.throwException
import chisel3._
import chisel3.internal.firrtl.KnownWidth
import chisel3.internal.sourceinfo.{SourceInfo, SourceInfoWhiteboxTransform}

import scala.language.experimental.macros
import scala.language.implicitConversions



package object sha3 {

  //  implicit class fromSeqToVec[T <: Data](seq: Seq[T]) {
  //    def toVec = VecInit(seq)
  //  }




  class Counter(n: Int) extends chisel3.util.Counter(n) {
    def reset() = value := 0.U
  }

  object Counter {
    def apply(n: Int) = new Counter(n)
  }

  implicit def saneCounterToUInt(c: Counter): UInt = c.value

  implicit def bitsToUInt(b: Bits): UInt = b.asUInt
}

package chisel3 {
  object rotate {

    implicit class RotatableUInt(u: UInt) extends {

      final def rotateLeft(that: Int): UInt = macro SourceInfoWhiteboxTransform.thatArg

      def do_rotateLeft(n: Int)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): UInt = u.width match {
        case KnownWidth(w) if n > w =>
          throwException("rotateLeft parameter is greater than width")
        case _ if (n == 0) => this.u
        case _ if (n < 0) => do_rotateRight(-n)
        case _ => u.tail(n) ## u.head(n)
      }

      final def rotateRight(that: Int): UInt = macro SourceInfoWhiteboxTransform.thatArg

      def do_rotateRight(n: Int)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): UInt = u.width match {
        case KnownWidth(w) if n >= w => do_rotateRight(n % w)
        case _ if (n <= 0) => do_rotateLeft(-n)
        case _ => this.u(n - 1, 0) ## (this.u >> n)
      }

      //    def rotateLeft(n: Int): UInt =
      //      if (n == 0) u
      //      else if (n < 0) this.rotateRight(-n)
      //      else u.tail(n) ## u.head(n)

      //    def rotateRight(n: Int): UInt =
      //      if (n <= 0) this.rotateLeft(-n)
      //      else u(n - 1, 0) ## (u >> n)
    }

  }
}