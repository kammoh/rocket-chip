import chisel3._
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}

class Ifc extends Module {
  val io = IO(new Bundle {
    val en = Input(Bool())
    val in = Input(UInt(2.W))
    val out = Output(UInt(3.W))
  })
}

class Tester extends ChiselFlatSpec {

  "Explicit type" should s"not expand" in {
    Driver(() => new Ifc {
      val w = WireInit( io.in.cloneType, init=io.in)
      when ( io.en) {
        w := 7.U
      }
      io.out := w
    }, "treadle") {
      c => new PeekPokeTester(c) {
        poke( c.io.en, 1)
        expect( c.io.out, 3)
      }
    } should be (true)
  }

  "Implicit type" should s"expand" in {
    Driver(() => new Ifc {
      val w = WireInit( io.in)
      when ( io.en) {
        w := 7.U
      }
      io.out := w
    }, "treadle") {
      c => new PeekPokeTester(c) {
        poke( c.io.en, 1)
        expect( c.io.out, 7)
      }
    } should be (true)
  }

}