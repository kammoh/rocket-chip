package sha3

import java.io.{File, PrintWriter}
import java.nio.file.{Files, StandardCopyOption}

import firrtl.FirrtlExecutionSuccess
import firrtl.options.{InputAnnotationFileAnnotation, TargetDirAnnotation}
import firrtl.passes.memlib.{InferReadWriteAnnotation, ReplSeqMem, ReplSeqMemAnnotation}
import firrtl.stage._
import firrtl.stage.FirrtlStage
import firrtl.stage.phases.DriverCompatibility
import freechips.rocketchip.util.{GeneratorApp, ParsedInputNames}
import org.fusesource.scalate.TemplateEngine
import org.joda.time.DateTime


//class TestHarness(implicit p: Parameters)
//  extends freechips.rocketchip.system.TestHarness

import java.nio.file.Files

trait VerilogGeneratorApp extends GeneratorApp {
  val CONFIG = names.configs
  val MODEL = names.topModuleClass
  //  val PROJECT = names.configProject


  val tdFull = s"${os.pwd}/$td"

  new File(tdFull).mkdirs()

  generateAnno


  lazy val (firrtlVerilogOutputs, memConfFile) = {
    val memConfFile = s"$longName.conf"
    val annosx = {
      println("running firrtl...")
      new FirrtlStage().run(
        Seq(
          FirrtlCircuitAnnotation(chisel3.Driver.toFirrtl(circuit)),
          TargetDirAnnotation(td),
          InputAnnotationFileAnnotation(longName + ".anno.json"),
          OutputFileAnnotation(longName + ".v"),
          InferReadWriteAnnotation,
          RunFirrtlTransformAnnotation(new ReplSeqMem),
          ReplSeqMemAnnotation("", s"$td/$memConfFile"),
        )
      )
    }

    DriverCompatibility.firrtlResultView(annosx) match {

      case success: FirrtlExecutionSuccess =>
        //      success.circuitState.circuit.modules.collect { case m: ExtModule => m.name + ".v" } :+
        (Seq(s"$longName.v"), memConfFile)
    }
  }

  lazy val ramVerilogFiles = {
    val filename = s"$longName.behav_srams.v"

    val memConf = s"$td/$memConfFile" // run firrtl
    println("running vlsi_mem_gen (behavioral)...")
    os.proc("scripts/vlsi_mem_gen", Seq(memConf, "-o", s"$td/$filename"))
      .call()
    Seq(filename)
  }

  lazy val resVerilogFiles = {
    val vfiles = Seq("AsyncResetReg.v", "ClockDivider2.v", "ClockDivider3.v", "EICG_wrapper.v", "RoccBlackBox.v",
      "plusarg_reader.v")

    for (res <- vfiles) {
      Files.copy(getClass.getResourceAsStream("/vsrc/" + res),
        (os.pwd / os.RelPath(td) / res).toNIO, StandardCopyOption.REPLACE_EXISTING)
    }

    vfiles
  }


  def verilogSources = resVerilogFiles ++ ramVerilogFiles ++ firrtlVerilogOutputs

  generateArtifacts
  generateROMs
}


trait SynthesisGeneratorApp extends VerilogGeneratorApp {

  val engine = new TemplateEngine


  def optsToString(opts: Map[String, Any]): String = {
    opts.collect({
      case (k, true) => "-" + k
      case (k, str: String) => "-" + k + " " + str
      case (k, Some(str)) => "-" + k + " " + str
      case (k, seq: Seq[String]) => "-" + k + " " + seq.mkString(",")
    }).mkString(" ")
  }

  def render(tclFileName: String, options: Map[String, Any]) = {
    os.write.over(
      os.pwd / os.RelPath(td) / tclFileName,

      engine.layout("/flow/" + tclFileName + ".mustache",
        options
      ))
  }

  def run(args: String*) = {
    os.proc(args)
      .call(cwd = os.pwd / os.RelPath(td), stdout = os.Inherit, stderr = os.Inherit)
  }
}

object EmulatorGeneratorApp extends VerilogGeneratorApp {
  val VERILATOR_THREADS = 4

  val RISCV = scala.sys.env.getOrElse("RISCV", "")


  val verilatorDir = s"$tdFull/$longName.verilator"
  val verilatorPath = os.Path(verilatorDir)

  if (os.exists(verilatorPath)) {
    os.remove.all(verilatorPath)
  }
  os.makeDir.all(verilatorPath)


  val CXXFLAGS = s"-std=c++11 -I$RISCV/include"
  val LDFLAGS = s"-L$RISCV/lib -Wl,-rpath,$RISCV/lib -lfesvr -lpthread"

  val cppfiles = Seq("emulator.cc", "SimDTM.cc", "SimJTAG.cc", "remote_bitbang.cc")
    .map(f => "csrc/" + f)

  val hfiles = Seq("verilator.h", "remote_bitbang.h")
    .map(f => "csrc/" + f)

  val sim_vfiles = Seq("SimDTM.v", "SimJTAG.v", "TestDriver.v", "plusarg_reader.v")
    .map(f => "vsrc/" + f)


  os.makeDir.all(verilatorPath / "csrc")
  os.makeDir.all(verilatorPath / "vsrc")
  for (res <- cppfiles ++ hfiles ++ sim_vfiles) {
    Files.copy(getClass.getResourceAsStream("/" + res), (verilatorPath / os.RelPath(res)).toNIO,
      StandardCopyOption.REPLACE_EXISTING)
  }

  val traceOpt = "--trace-fst-thread"

  val CFLAGS = CXXFLAGS + "-O3 -g0 -fomit-frame-pointer -march=native -mtune=native -DVERILATOR " +
    s"-DTEST_HARNESS=V$MODEL" +
    Seq("csrc/verilator.h").map(h => s" -include $h").mkString +
    s" -include $tdFull/$longName.plusArgs ${if (traceOpt.contains("fst")) "-DVM_TRACE_FST" else ""}"

  val model_header = s"$verilatorDir/V$MODEL.h"
  val VERILATOR_FLAGS = Seq("--top-module", MODEL, "+define+PRINTF_COND=$c(\"verbose\",\"&&\",\"done_reset\")",
    "+define+RANDOMIZE_GARBAGE_ASSIGN", "+define+STOP_COND=$c(\"done_reset\")", "--assert",
    "--output-split", "20000", "--output-split-cfuncs", "20000", "--threads", s"$VERILATOR_THREADS",
    "-Wno-UNOPTTHREADS", "-Wno-STMTDLY", "--x-assign", "unique", "--x-initial", "unique",
    s"-I$verilatorDir/vsrc", "-O3 ", "-CFLAGS", CFLAGS) ++
    Seq("--cc", "--exe", "-Mdir", verilatorDir, traceOpt, "-o", s"$tdFull/emulator.$longName") ++
    verilogSources ++ sim_vfiles ++ cppfiles ++
    Seq("-LDFLAGS", LDFLAGS, "-CFLAGS", s"-I$tdFull -include $model_header")

  //  println(s"PATH=${sys.env("PATH")}")
  //  println(s"VERILATOR_FLAGS=${VERILATOR_FLAGS.mkString(" ")}")
  println(s"running verilator...")
  os.proc("verilator", VERILATOR_FLAGS)
    .call(cwd = os.pwd, Map("OBJCACHE" -> "ccache"))

  println("running make...")
  os.proc("make", Seq("-f", s"V$MODEL.mk", "-j", "8"))
    .call(cwd = os.Path(verilatorDir))

  printf(s"build of $tdFull/emulator.$longName finished successfully!")
  // time ./emulator.sha3.WithSha3Config +verbose +cycle-count -v sha3.dump.fst  +dump-start=2350000 +max_core_cycles=3100000 pk ../../sha3/src/main/c/test_build/test.rv 3>&1 1>&2 2>&3 | spike-dasm > sha3.dump.out
}

object DcSynthesisGeneratorApp extends SynthesisGeneratorApp {
  override lazy val names: ParsedInputNames = {
    val args = "emulator/generated-src freechips.rocketchip.system TestHarness sha3 WithSha3Config"
      .split("\\s+").toList

    println(s"args=${args}")

    val base =
      ParsedInputNames(
        targetDir = args(0),
        topModuleProject = args(1),
        topModuleClass = args(2),
        configProject = args(3),
        configs = args(4),
        outputBaseName = None)

    if (args.length == 6) {
      base.copy(outputBaseName = Some(args(5)))
    } else {
      base
    }
  }


  val tclFileName = "dc.tcl"

  override lazy val ramVerilogFiles = {
    val filename = s"$longName.bb_srams.v"
    val memConf = s"$td/$memConfFile" // run firrtl
    println("running vlsi_mem_gen (blackbox)...")
    os.proc("scripts/vlsi_mem_gen", Seq(memConf, "--blackbox", "-o", s"$td/$filename"))
      .call()
    Seq(filename)
  }

  var options = Map(
    "top_module_name" -> "ExampleRocketSystem",
    "verilog_sources" -> verilogSources.map(path => Map("path" -> path)),
    "output_dir" -> ".",
    "clock_period" -> 1.0,
    "clock_net" -> "clock",
    "rtk_path" -> "/src/SAED32nm/rvt_tt_1p05v_25c",
    "max_cores" -> 4,
    "optimization_flow" -> "hplp", //"rtm_exp",
  )

  render(tclFileName, options)

  println("running dc_shell...")

  run("dc_shell-xg-t", "-topographical_mode", "-f", tclFileName)

}

object VivadoSynthesisGeneratorApp extends SynthesisGeneratorApp {
  override lazy val names: ParsedInputNames = {
    val args = "emulator/generated-src freechips.rocketchip.system TestHarness sha3 WithSha3Config"
      .split("\\s+").toList

    ParsedInputNames(
      targetDir = args(0),
      topModuleProject = args(1),
      topModuleClass = args(2),
      configProject = args(3),
      configs = args(4),
      outputBaseName = None)
  }

  val clockXdcFileName = "clock.xdc"
  val tclFileName = "vivado.tcl"


  val options: Map[String, Map[String, Any]] = Map(
    "synth" -> Map(
      "quiet" -> false,
      //        "resource_sharing" -> "on",
      "retiming" -> true,
      "directive" -> "AreaOptimized_high",
      "flatten_hierarchy" -> "rebuilt"
    ),
    "opt" -> Map(
      "directive" -> Some("ExploreWithRemap"),
      "quiet" -> false,
    ),
    "pys_opt" -> Map(
      "retime" -> true,
      "hold_fix" -> true,
      "rewire" -> true
    ),
    "common" -> Map("part" -> "xc7a100tcsg324-1")
  )

  val part = options("common")("part")

  val myOptions =
      Map(
        "part" -> part,
        "top_module_name" -> circuit.name,
        "verilog_sources" -> verilogSources.map(path => Map("path" -> path)),
        "xdcs" -> Seq(Map("path" -> clockXdcFileName)),
        "output_dir" -> ".",
        "synth_options" -> optsToString(options("synth") ++ Map("top" -> circuit.name,
          "part" -> part)),
        "opt_options" -> optsToString(options("opt")),
        "phys_opt_options" -> optsToString(options("pys_opt"))
      )

  val target_frequency = options("common")("target_frequency").asInstanceOf[Double]

  os.write.over(os.pwd / os.RelPath(td), clockXdcFileName, engine.layout("/" + clockXdcFileName + ".mustache",
    Map(
      "period" -> f"${1000.0 / target_frequency}%.3f", "name" -> "clock", "port_name" -> "clock"
    )
  ))


  render(tclFileName, myOptions)

  println("running vivado...")

  run("vivado", "-mode", "batch", "-nojournal", "-notrace",
    "-source", tclFileName)

}