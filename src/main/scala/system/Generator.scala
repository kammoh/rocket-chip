// See LICENSE.SiFive for license details.

package freechips.rocketchip.system

import java.io.File

import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.tile.XLen
import freechips.rocketchip.util.{GeneratorApp, ParsedInputNames}

import scala.collection.mutable.LinkedHashSet

/** A Generator for platforms containing Rocket Subsystemes */
object Generator extends GeneratorApp {

  val rv64RegrTestNames = LinkedHashSet(
        "rv64ud-v-fcvt",
        "rv64ud-p-fdiv",
        "rv64ud-v-fadd",
        "rv64uf-v-fadd",
        "rv64um-v-mul",
        "rv64mi-p-breakpoint",
        "rv64uc-v-rvc",
        "rv64ud-v-structural",
        "rv64si-p-wfi",
        "rv64um-v-divw",
        "rv64ua-v-lrsc",
        "rv64ui-v-fence_i",
        "rv64ud-v-fcvt_w",
        "rv64uf-v-fmin",
        "rv64ui-v-sb",
        "rv64ua-v-amomax_d",
        "rv64ud-v-move",
        "rv64ud-v-fclass",
        "rv64ua-v-amoand_d",
        "rv64ua-v-amoxor_d",
        "rv64si-p-sbreak",
        "rv64ud-v-fmadd",
        "rv64uf-v-ldst",
        "rv64um-v-mulh",
        "rv64si-p-dirty")

  val rv32RegrTestNames = LinkedHashSet(
      "rv32mi-p-ma_addr",
      "rv32mi-p-csr",
      "rv32ui-p-sh",
      "rv32ui-p-lh",
      "rv32uc-p-rvc",
      "rv32mi-p-sbreak",
      "rv32ui-p-sll")


  override def addTestSuites {
    import DefaultTestSuites._
    val xlen = params(XLen)
    // TODO: for now only generate tests for the first core in the first subsystem
    params(RocketTilesKey).headOption.map { tileParams =>
      val coreParams = tileParams.core
      val vm = coreParams.useVM
      val env = if (vm) List("p","v") else List("p")
      coreParams.fpu foreach { case cfg =>
        if (xlen == 32) {
          TestGeneration.addSuites(env.map(rv32uf))
          if (cfg.fLen >= 64)
            TestGeneration.addSuites(env.map(rv32ud))
        } else {
          TestGeneration.addSuite(rv32udBenchmarks)
          TestGeneration.addSuites(env.map(rv64uf))
          if (cfg.fLen >= 64)
            TestGeneration.addSuites(env.map(rv64ud))
        }
      }
      if (coreParams.useAtomics) {
        if (tileParams.dcache.flatMap(_.scratch).isEmpty)
          TestGeneration.addSuites(env.map(if (xlen == 64) rv64ua else rv32ua))
        else
          TestGeneration.addSuites(env.map(if (xlen == 64) rv64uaSansLRSC else rv32uaSansLRSC))
      }
      if (coreParams.useCompressed) TestGeneration.addSuites(env.map(if (xlen == 64) rv64uc else rv32uc))
      val (rvi, rvu) =
        if (xlen == 64) ((if (vm) rv64i else rv64pi), rv64u)
        else            ((if (vm) rv32i else rv32pi), rv32u)

      TestGeneration.addSuites(rvi.map(_("p")))
      TestGeneration.addSuites((if (vm) List("v") else List()).flatMap(env => rvu.map(_(env))))
      TestGeneration.addSuite(benchmarks)
      TestGeneration.addSuite(new RegressionTestSuite(if (xlen == 64) rv64RegrTestNames else rv32RegrTestNames))
    }
  }

  val longName = names.configProject + "." + names.configs
  generateFirrtl
  generateAnno
  generateTestSuiteMakefrags
  generateROMs
  generateArtefacts
}

object SbtRocketGenerator extends GeneratorApp {
  override lazy val names = ParsedInputNames(
    targetDir = "emulator/target",
    topModuleProject = "freechips.rocketchip.system",
    topModuleClass = "TestHarness",
    configProject = "sha3",
    configs = s"WithSha3Config")

  override val longName = names.configProject + "." + names.configs

  //  new File(td).mkdirs()

  val nm = s"$td/${names.configProject}.${names.configs}"
  val generatedDir = s"$td/generated_src"

  new File(generatedDir).mkdirs()

  val firrtlArgs = Array("-i", s"$nm.fir" , "-o", s"$nm.v", "-X", "verilog", "--infer-rw", names.topModuleClass, "--repl-seq-mem",
    s"-c:${names.topModuleClass}:-o:$nm.rom.conf",
    "-faf", s"$nm.anno.json",
    "-td", s"$generatedDir/$longName/")


  chisel3.Driver.dumpFirrtl(circuit, Some(new File(td, s"$longName.fir")))
  generateAnno
  generateTestSuiteMakefrags
  generateROMs
  generateArtefacts

  println("running firrtl " + firrtlArgs.mkString(" "))
  firrtl.stage.FirrtlMain.main(firrtlArgs)
}
