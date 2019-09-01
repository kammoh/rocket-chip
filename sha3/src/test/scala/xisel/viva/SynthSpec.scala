package xisel.viva

import chisel3.experimental.MultiIOModule
import chisel3.stage._
import chisel3.tester.experimental.sanitizeFileName
import chisel3.{ChiselExecutionSuccess, HasChiselExecutionOptions}
import firrtl.ir.ExtModule
import firrtl.stage.FirrtlStage
import firrtl.{ExecutionOptionsManager, FirrtlExecutionSuccess, HasFirrtlOptions}
import org.fusesource.scalate.TemplateEngine
import org.joda.time.DateTime
import org.scalatest.{FlatSpec, Matchers}

import scala.sys.process.Process
import scala.util.DynamicVariable
import scala.reflect.runtime.universe.{TypeTag, typeTag}

trait SynthSpec extends FlatSpec with Matchers {

  protected var scalaTestContext = new DynamicVariable[Option[NoArgTest]](None)

  val defaultOptions: Map[String, Map[String, Any]] = Map(
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

  def synth[T <: MultiIOModule : TypeTag](dut: () => T, options0: Map[String, Map[String, Any]]): Int = {

    val options = defaultOptions ++ options0

    val target_frequency = options("common")("target_frequency").asInstanceOf[Double]

    def getRetTypeName[T: TypeTag](obj: T) = typeTag[T].tpe.typeArgs.last.toString

    val synthRunPath = os.pwd / "synth" / getRetTypeName(dut) /
      sanitizeFileName(DateTime.now().toString("yy-MM-dd_HH:mm:ss"))

    println(s"Running vivado in $synthRunPath")

    val optionsManager = new ExecutionOptionsManager("chisel3")
      with HasChiselExecutionOptions with HasFirrtlOptions {
      commonOptions = commonOptions.copy(
        targetDirName = synthRunPath.toString
      )
    }

    val part = options("common")("part")

    def optsToString(opts: Map[String, Any]): String = {
      opts.collect({
        case (k, true) => "-" + k
        case (k, str: String) => "-" + k + " " + str
        case (k, Some(str)) => "-" + k + " " + str
        case (k, seq: Seq[String]) => "-" + k + " " + seq.mkString(",")
      }).mkString(" ")
    }

    //    def optsToString(opts:

    //    val stage = new FirrtlStage
    //
    //    stage.execute(Array("-X", "sverilog", "-E", "sverilog", "-foaf", "foo.json"), Seq.empty)


    chisel3.Driver.execute(optionsManager, dut) match {
      case ChiselExecutionSuccess(Some(circuit), _, Some(success: FirrtlExecutionSuccess)) =>

        val clockXdcFileName = "clock.xdc"
        val tclFileName = "vivado.tcl"

        val verilog_sources = success.circuitState.circuit.modules.collect { case m: ExtModule => m.name + ".v" } :+
          s"${circuit.name}.v"

        val engine = new TemplateEngine
        os.write.over(synthRunPath / tclFileName,
          engine.layout("/" + tclFileName + ".mustache",
            Map(
              "part" -> part,
              "top_module_name" -> circuit.name,
              "verilog_sources" -> verilog_sources.map(path => Map("path" -> path)),
              "xdcs" -> Seq(Map("path" -> clockXdcFileName)),
              "output_dir" -> ".",
              "synth_options" -> optsToString(options("synth") ++ Map("top" -> circuit.name,
                "part" -> part)),
              "opt_options" -> optsToString(options("opt")),
              "phys_opt_options" -> optsToString(options("pys_opt"))
            )
          )
        )

        os.write.over(synthRunPath / clockXdcFileName, engine.layout("/" + clockXdcFileName + ".mustache",
          Map(
            "period" -> f"${1000.0 / target_frequency}%.3f", "name" -> "clock", "port_name" -> "clock"
          )
        ))

        Process(Seq("vivado", "-mode", "batch", "-nojournal", "-notrace",
          "-source", tclFileName), synthRunPath.toIO).!
    }
  }
}
