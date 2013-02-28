/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import java.io.PrintStream
import java.lang.management.{ ManagementFactory, ThreadInfo }
import java.util.Date
import java.util.concurrent.CountDownLatch
import org.scalatest.{ BeforeAndAfterAll, Suite }
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * The Coroner can be used to print a diagnostic report of the JVM state,
 * including stack traces and deadlocks. A report can be printed directly, by
 * calling `printReport`. Alternatively, the Coroner can be asked to `watch`
 * the JVM and generate a report at a later time - unless the Coroner is cancelled
 * by that time.
 *
 * The latter method is useful for printing diagnostics in the event that, for
 * example, a unit test stalls and fails to cancel the Coroner in time. The
 * Coroner will assume that the test has "died" and print a report to aid in
 * debugging.
 */
object Coroner {

  /**
   * Used to cancel the Coroner after calling `watch`.
   */
  trait WatchHandle {
    def cancel(): Unit
  }

  /**
   * Ask the Coroner to print a report if it is not cancelled by the given deadline.
   * The returned handle can be used to perform the cancellation.
   */
  def watch(deadline: Deadline, reportTitle: String, out: PrintStream): WatchHandle = {
    val duration = deadline.timeLeft // Store for later reporting
    val cancelLatch = new CountDownLatch(1)

    @tailrec def watchLoop() {
      if (deadline.isOverdue) {
        triggerReport()
      } else {
        val cancelled = try {
          cancelLatch.await(deadline.timeLeft.length, deadline.timeLeft.unit)
        } catch {
          case _: InterruptedException ⇒ false
        }
        if (cancelled) {
          // Our job is finished, let the thread stop
        } else {
          watchLoop()
        }
      }
    }

    def triggerReport() {
      out.println(s"Coroner not cancelled after ${duration.toMillis}ms. Looking for signs of foul play...")
      try {
        printReport(reportTitle, out)
      } catch {
        case NonFatal(ex) ⇒ {
          out.println("Error displaying Coroner's Report")
          ex.printStackTrace(out)
        }
      }
    }

    val thread = new Thread(new Runnable { def run = watchLoop() }, "Coroner")
    thread.start() // Must store thread in val to work around SI-7203

    new WatchHandle {
      def cancel() { cancelLatch.countDown() }
    }
  }

  /**
   * Print a report containing diagnostic information.
   */
  def printReport(reportTitle: String, out: PrintStream) {
    import out.println

    val osMx = ManagementFactory.getOperatingSystemMXBean()
    val rtMx = ManagementFactory.getRuntimeMXBean()
    val memMx = ManagementFactory.getMemoryMXBean()
    val threadMx = ManagementFactory.getThreadMXBean()

    println(s"""#Coroner's Report: $reportTitle
                #OS Architecture: ${osMx.getArch()}
                #Available processors: ${osMx.getAvailableProcessors()}
                #System load (last minute): ${osMx.getSystemLoadAverage()}
                #VM start time: ${new Date(rtMx.getStartTime())}
                #VM uptime: ${rtMx.getUptime()}ms
                #Heap usage: ${memMx.getHeapMemoryUsage()}
                #Non-heap usage: ${memMx.getNonHeapMemoryUsage()}""".stripMargin('#'))

    def dumpAllThreads(): Seq[ThreadInfo] = {
      threadMx.dumpAllThreads(
        threadMx.isObjectMonitorUsageSupported(),
        threadMx.isSynchronizerUsageSupported())
    }

    def findDeadlockedThreads(): (Seq[ThreadInfo], String) = {
      val (ids, desc) = if (threadMx.isSynchronizerUsageSupported()) {
        (threadMx.findDeadlockedThreads(), "monitors and ownable synchronizers")
      } else {
        (threadMx.findMonitorDeadlockedThreads(), "monitors, but NOT ownable synchronizers")
      }
      if (ids == null) {
        (Seq.empty, desc)
      } else {
        val maxTraceDepth = 1000 // Seems deep enough
        (threadMx.getThreadInfo(ids, maxTraceDepth), desc)
      }
    }

    def printThreadInfo(threadInfos: Seq[ThreadInfo]) = {
      if (threadInfos.isEmpty) {
        println("None")
      } else {
        for (ti ← threadInfos.sortBy(_.getThreadName)) { println(ti) }
      }
    }

    println("All threads:")
    printThreadInfo(dumpAllThreads())

    val (deadlockedThreads, deadlockDesc) = findDeadlockedThreads()
    println(s"Deadlocks found for $deadlockDesc:")
    printThreadInfo(deadlockedThreads)
  }

}

/**
 * Mixin for tests that should be watched by the Coroner. The `startCoroner`
 * and `stopCoroner` methods should be called before and after the test runs.
 * The Coroner will display its report if the test takes longer than the
 * (dilated) `expectedTestDuration` to run.
 */
trait WatchedByCoroner {
  self: TestKit ⇒

  @volatile private var coronerWatch: Coroner.WatchHandle = _

  final def startCoroner() {
    coronerWatch = Coroner.watch(expectedTestDuration.dilated.fromNow, getClass.getName, System.err)
  }

  final def stopCoroner() {
    coronerWatch.cancel()
    coronerWatch = null
  }

  def expectedTestDuration: FiniteDuration
}