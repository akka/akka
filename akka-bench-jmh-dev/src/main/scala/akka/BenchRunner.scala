package akka

import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.CommandLineOptions

object BenchRunner extends App {
  import scala.collection.JavaConversions._

  val args2 = args.toList match {
    case "quick" :: tail => "-i 1 -wi 1 -f1 -t1".split(" ").toList ::: tail
    case "full" :: tail => "-i 10 -wi 4 -f3 -t1".split(" ").toList ::: tail
    case other => other
  }

  val opts = new CommandLineOptions(args2: _*)
  val results = new Runner(opts).run()

  val report = results.map { result: RunResult ⇒
    val bench = result.getParams.getBenchmark
    val params = result.getParams.getParamsKeys.map(key => s"$key=${result.getParams.getParam(key)}").mkString("_")
    val score = result.getAggregatedResult.getPrimaryResult.getScore.round
    val unit = result.getAggregatedResult.getPrimaryResult.getScoreUnit
    s"\t${bench}_${params}\t$score\t$unit"
  }

  report.toList.sorted.foreach(println)
}
