/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import java.io._
import java.lang.management.ManagementFactory
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import scala.concurrent.duration._
import scala.concurrent.Await

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CoronerSpec extends WordSpec with MustMatchers {

  private def captureOutput[A](f: PrintStream ⇒ A): (A, String) = {
    val bytes = new ByteArrayOutputStream()
    val out = new PrintStream(bytes, true, "UTF-8")
    val result = f(out)
    (result, new String(bytes.toByteArray(), "UTF-8"))
  }

  "A Coroner" must {

    "generate a report if enough time passes" taggedAs TimingTest in {
      val (_, report) = captureOutput(out ⇒ Await.ready(Coroner.watch(100.milliseconds.fromNow, "XXXX", out), 1.second))
      report must include("Coroner's Report")
      report must include("XXXX")
    }

    "not generate a report if cancelled early" taggedAs TimingTest in {
      val (_, report) = captureOutput(out ⇒ {
        val coroner = Coroner.watch(100.milliseconds.fromNow, "XXXX", out)
        coroner.cancel()
        Await.ready(coroner, 1.second)
      })
      report must be("")
    }

    "display deadlock information in its report" in {

      // Create two threads that each recursively synchronize on a list of
      // objects. Give each thread the same objects, but in reversed order.
      // Control execution of the threads so that they each hold an object
      // that the other wants to synchronize on. BOOM! Deadlock. Generate a
      // report, then clean up and check the report contents.

      case class LockingThread(name: String, thread: Thread, ready: Semaphore, proceed: Semaphore)

      def lockingThread(name: String, initialLocks: List[ReentrantLock]): LockingThread = {
        val ready = new Semaphore(0)
        val proceed = new Semaphore(0)
        val t = new Thread(new Runnable {
          def run = try recursiveLock(initialLocks) catch { case _: InterruptedException ⇒ () }

          def recursiveLock(locks: List[ReentrantLock]) {
            locks match {
              case Nil ⇒ ()
              case lock :: rest ⇒ {
                ready.release()
                proceed.acquire()
                lock.lockInterruptibly() // Allows us to break deadlock and free threads
                try {
                  recursiveLock(rest)
                } finally {
                  lock.unlock()
                }
              }
            }
          }
        }, name)
        t.start()
        LockingThread(name, t, ready, proceed)
      }

      val x = new ReentrantLock()
      val y = new ReentrantLock()
      val a = lockingThread("deadlock-thread-a", List(x, y))
      val b = lockingThread("deadlock-thread-b", List(y, x))

      // Walk threads into deadlock
      a.ready.acquire()
      b.ready.acquire()
      a.proceed.release()
      b.proceed.release()
      a.ready.acquire()
      b.ready.acquire()
      a.proceed.release()
      b.proceed.release()

      val (_, report) = captureOutput(Coroner.printReport("Deadlock test", _))

      a.thread.interrupt()
      b.thread.interrupt()

      report must include("Coroner's Report")

      // Split test based on JVM capabilities. Not all JVMs can detect
      // deadlock between ReentrantLocks. However, we need to use
      // ReentrantLocks because normal, monitor-based locks cannot be
      // un-deadlocked once this test is finished.

      val threadMx = ManagementFactory.getThreadMXBean()
      if (threadMx.isSynchronizerUsageSupported()) {
        val sectionHeading = "Deadlocks found for monitors and ownable synchronizers"
        report must include(sectionHeading)
        val deadlockSection = report.split(sectionHeading)(1)
        deadlockSection must include("deadlock-thread-a")
        deadlockSection must include("deadlock-thread-b")
      } else {
        val sectionHeading = "Deadlocks found for monitors, but NOT ownable synchronizers"
        report must include(sectionHeading)
        val deadlockSection = report.split(sectionHeading)(1)
        deadlockSection must include("None")
        deadlockSection must not include ("deadlock-thread-a")
        deadlockSection must not include ("deadlock-thread-b")
      }
    }

  }
}
