/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.testkit

import PerfFlamesSupport._
import akka.remote.testconductor.RoleName

import scala.concurrent.Future
import scala.concurrent.duration._

import scala.language.postfixOps

object PerfFlamesSupport {
  val Command = "perf-java-flames"
}

/**
 * INTERNAL API: Support trait allowing trivially recording perf metrics from [[MultiNodeSpec]]s
 */
private[akka] trait PerfFlamesSupport { _: MultiNodeSpec ⇒

  /**
   * Runs `perf-java-flames` script on given node (JVM process).
   * Refer to https://github.com/jrudolph/perf-map-agent for options and manual.
   *
   * Options are currently to be passed in via `export PERF_MAP_OPTIONS` etc.
   */
  def runPerfFlames(nodes: RoleName*)(delay: FiniteDuration, time: FiniteDuration = 15.seconds): Unit = {
    if (isPerfJavaFlamesAvailable && isNode(nodes: _*)) {
      import scala.concurrent.ExecutionContext.Implicits.global

      val afterDelay = akka.pattern.after(delay, system.scheduler)(Future.successful("GO!"))
      afterDelay onComplete { it ⇒
        import java.lang.management._
        val name = ManagementFactory.getRuntimeMXBean.getName
        val pid = name.substring(0, name.indexOf('@')).toInt

        val perfCommand = s"$Command $pid"
        println(s"[perf @ $myself($pid)][OUT]: " + perfCommand)

        import scala.sys.process._
        perfCommand.run(new ProcessLogger {
          override def buffer[T](f: ⇒ T): T = f
          override def out(s: ⇒ String): Unit = println(s"[perf @ $myself($pid)][OUT] " + s)
          override def err(s: ⇒ String): Unit = println(s"[perf @ $myself($pid)][ERR] " + s)
        })
      }
    }
  }

  def isPerfJavaFlamesAvailable: Boolean = {
    import scala.sys.process._
    val isIt = (s"type $Command" !) == 0
    if (!isIt) println(s"WARN: perf-java-flames not on the path! Skipping perf profiling.")
    isIt
  }
}
