package sha3

import java.io.{File, PrintWriter}
import java.nio.file.{Files, StandardCopyOption}

import chisel3.tester.experimental.sanitizeFileName
import firrtl.FirrtlExecutionSuccess
import firrtl.options.{InputAnnotationFileAnnotation, TargetDirAnnotation}
import firrtl.passes.memlib.{InferReadWriteAnnotation, ReplSeqMem, ReplSeqMemAnnotation}
import firrtl.stage._
import firrtl.stage.FirrtlStage
import firrtl.stage.phases.DriverCompatibility
import freechips.rocketchip.util.{GeneratorApp, ParsedInputNames}
import org.fusesource.scalate.TemplateEngine
import org.joda.time.DateTime
import toml.Value
import toml.Value._


//class TestHarness(implicit p: Parameters)
//  extends freechips.rocketchip.system.TestHarness

import java.nio.file.Files

trait VerilogGenerator {
  this: GeneratorApp =>
  val CONFIG = names.configs
  val MODEL = names.topModuleClass
  //  val PROJECT = names.configProject


  val tdFull = s"${os.pwd}/$td"

  new File(tdFull).mkdirs()


  lazy val firrtlCircuit = {
    println(s"Target directory: $td")
    generateAnno
    chisel3.Driver.toFirrtl(circuit)
  }

  lazy val clocks = firrtlCircuit.modules(0).ports.collect{
    case firrtl.ir.Port(_, name, firrtl.ir.Input, firrtl.ir.ClockType) => name
  }


  lazy val (firrtlVerilogOutputs, memConfFile) = {
    val memConfFile = s"$longName.conf"
    val annosx = {
      println("running firrtl...")
      new FirrtlStage().run(
        Seq(
          FirrtlCircuitAnnotation(firrtlCircuit),
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


trait SynthesisGenerator extends VerilogGenerator {
  this: GeneratorApp =>

  val engine = new TemplateEngine

  def parseMap(vmap: Map[String, Value]): Map[String, Any] = {
    def mapValue(v: Value): Any = {
      v match {
        case Str(value) => value
        case Bool(value) => value
        case Real(value) => value.toString
        case Num(value) => value.toString
        case Tbl(values) => parseMap(values)
        case Arr(values) => values.map(mapValue)
      }
    }

    vmap.mapValues(mapValue)
  }

  def parseTomlOptions(section: String, tomlPath: os.Path): Map[String, Any] = {
    toml.Toml.parse(os.read(tomlPath)) match {
      case Right(Tbl(map: Map[String, Value])) =>
        map(section) match {
          case Tbl(rtkOptions) =>
            parseMap(rtkOptions)
        }
    }
  }


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
    val res = os.proc(args)
      .call(cwd = os.pwd / os.RelPath(td), stdout = os.Inherit, stderr = os.Inherit)
    println(s"command result: ${res.toString()}")
    res.exitCode
  }
}

object EmulatorGenerator extends VerilogGenerator with GeneratorApp {
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

object DcSynthesisGeneratorApp extends SynthesisGenerator with GeneratorApp {
  lazy val toolName = "dc"
  lazy val toolCategory = "synth"
  lazy val configProject = "sha3"
  lazy val configs = "WithSha3Config"

  lazy val flowOptions = parseTomlOptions(toolName, os.pwd / "flow.toml")
  lazy val rtk = flowOptions("rtk").asInstanceOf[String]
  lazy val rtkOptions = parseTomlOptions(rtk, os.pwd / "technology.toml")

  override lazy val names: ParsedInputNames = {
    ParsedInputNames(
      targetDir = "generated/" + s"$toolCategory.$toolName.$rtk" + "/" + sanitizeFileName(configProject) + "." + sanitizeFileName(configs)
        + "_" + DateTime.now().toString("dd.MM.YYYY_HH.mm.ss"),
      topModuleProject = "freechips.rocketchip.system",
      topModuleClass = "ExampleRocketSystem",
      configProject = configProject,
      configs = configs,
      outputBaseName = None)
  }


  override lazy val ramVerilogFiles = {
    val filename = s"$longName.bb_srams.v"
    val memConf = s"$td/$memConfFile" // run firrtl
    println("running vlsi_mem_gen (blackbox)...")
    os.proc("scripts/vlsi_mem_gen", Seq(memConf, "--blackbox", "-o", s"$td/$filename"))
      .call()
    Seq(filename)
  }


  val targetFrequencyGhz = flowOptions("target_frequency") match {
    case d: Double => d
    case str: String =>
      str.trim.toLowerCase match {
        case a : String if a.endsWith("mhz") =>
          a.stripSuffix("mhz").strip.toDouble / 1000.0
        case a : String if a.endsWith("ghz") =>
          a.stripSuffix("ghz").strip.toDouble
      }
  }

  val timeScale = rtkOptions("time_scale") match {
    case str: String if str.trim == "1ps" => 1.0
    case str: String if str.trim == "1ns" => 1000.0
  }
  val options = Map(
    "top_module_name" -> names.topModuleClass,
    "verilog_sources" -> verilogSources.map(path => Map("path" -> path)),
    "output_dir" -> ".",
    "clock_period" -> ((1000.0 / targetFrequencyGhz) / timeScale).toString,
    "clock_net" -> clocks(0),
    "dont_touch_list" -> Seq("sha3Rocc"),
  )

  for(template <- flowOptions("scripts").asInstanceOf[Seq[String]] ) {
    render(template, options ++ flowOptions ++ rtkOptions)
  }

  val executable = flowOptions("executable").asInstanceOf[String]
  val toolArgs = flowOptions("tool_args").asInstanceOf[Seq[String]]

  println(s"running $executable...")

  run(executable +: toolArgs:_*)

}

object VivadoSynthesisGeneratorApp extends SynthesisGenerator with GeneratorApp {
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
