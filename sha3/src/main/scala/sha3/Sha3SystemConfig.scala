package sha3

import freechips.rocketchip.config.{Config, Parameters}
import freechips.rocketchip.diplomacy.{LazyModule, ValName}
import freechips.rocketchip.system.DefaultConfig
import freechips.rocketchip.tile.{BuildRoCC, OpcodeSet}
import freechips.rocketchip.unittest.UnitTests


class WithSha3 extends Config((site, here, up) => {
  case BuildRoCC =>
    implicit val valName = ValName("TestHarness")
    Seq((p: Parameters) => LazyModule(new Sha3Accelerator(OpcodeSet.custom0)(p)))
})

class WithSha3Config extends Config(new WithSha3 ++ new DefaultConfig)

//class WithSha3UnitTests extends Config((site, here, up) => {
//  case UnitTests => (p: Parameters) => Sha3UnitTests(p)
//})
//
//class Sha3UnitTestConfig extends Config(
//  new WithSha3 ++ new WithSha3UnitTests ++ new BaseConfig)
