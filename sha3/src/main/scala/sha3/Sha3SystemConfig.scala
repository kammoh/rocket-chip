package sha3

import freechips.rocketchip.config.{Config, Parameters}
import freechips.rocketchip.diplomacy.{LazyModule, SynchronousCrossing}
import freechips.rocketchip.rocket.{DCacheParams, ICacheParams, MulDivParams, RocketCoreParams}
import freechips.rocketchip.subsystem.{CacheBlockBytes, RocketCrossingKey, RocketCrossingParams, RocketTilesKey, SystemBusKey, TileMasterPortParams, With1TinyCore}
import freechips.rocketchip.system.{BaseConfig, DefaultConfig, DefaultFPGASmallConfig, WithNSmallCores}
import freechips.rocketchip.tile.{BuildRoCC, OpcodeSet, RocketTileParams, XLen}
//import freechips.rocketchip.unittest.UnitTests


//class WithSha3 extends Config((_, _, _) => {
//  case BuildRoCC =>
//    implicit val valName: ValName = ValName("TestHarness")
//    Seq((p: Parameters) => LazyModule(new Sha3Accelerator(OpcodeSet.custom0)(p)))
//})

class WithSha3 extends Config((site, here, up) => {
  case BuildRoCC => List(
    (p: Parameters) => {
      val sha3Rocc = LazyModule(new Sha3Accelerator(OpcodeSet.custom0)(p))
      sha3Rocc
    })
})

class WithMySmallConfig extends Config((site, here, up) => {
//  case XLen => 32

  case RocketTilesKey => {
    Seq(RocketTileParams(
      core = RocketCoreParams(
        useVM = false,
        fpu = None,
        //          mulDiv = Some(MulDivParams(mulUnroll = 1))),
      ),
//      btb = None,
      dcache = Some(DCacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 64,
        nWays = 4,
        nTLBEntries = 4,
        nMSHRs = 0,
        blockBytes = site(CacheBlockBytes))),
      icache = Some(ICacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 64,
        nWays = 2,
        nTLBEntries = 4,
        blockBytes = site(CacheBlockBytes)))))
  }

//  case RocketCrossingKey => List(RocketCrossingParams(
//    crossingType = SynchronousCrossing(),
//    master = TileMasterPortParams()
//  ))
})

class WithSha3Config extends Config(new WithSha3 ++ new WithMySmallConfig ++ new BaseConfig)

//class WithSha3UnitTests extends Config((site, here, up) => {
//  case UnitTests => (p: Parameters) => Sha3UnitTests(p)
//})
//
//class Sha3UnitTestConfig extends Config(
//  new WithSha3 ++ new WithSha3UnitTests ++ new BaseConfig)
