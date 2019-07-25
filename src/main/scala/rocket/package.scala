// See LICENSE.Berkeley for license details.

package freechips.rocketchip

import scala.language.implicitConversions
import chisel3.{Bits, SInt, UInt}

package object rocket extends rocket.constants.ScalarOpConstants with rocket.constants.MemoryOpConstants{

  implicit def bitsToUInt(b: Bits): UInt = b.asUInt
  implicit def bitsToSInt(b: Bits): SInt = b.asSInt
}
