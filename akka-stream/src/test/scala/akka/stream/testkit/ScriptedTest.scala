/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import org.scalatest._
import akka.actor.ActorSystem
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.concurrent.duration._
import scala.util.control.NonFatal
import akka.stream.scaladsl.Flow
import akka.stream.MaterializerSettings

trait ScriptedTest extends ShouldMatchers {

  class ScriptException(msg: String) extends RuntimeException(msg)

  object Script {
    def apply[In, Out](phases: (Seq[In], Seq[Out])*): Script[In, Out] = {
      var providedInputs = Vector[In]()
      var expectedOutputs = Vector[Out]()
      var jumps = Vector[Int]()

      for ((ins, outs) ← phases) {
        providedInputs ++= ins
        expectedOutputs ++= outs
        jumps ++= Vector.fill(ins.size - 1)(0) ++ Vector(outs.size)
      }

      Script(providedInputs, expectedOutputs, jumps, inputCursor = 0, outputCursor = 0, outputEndCursor = 0, completed = false)
    }
  }

  case class Script[In, Out](
    providedInputs: Vector[In],
    expectedOutputs: Vector[Out],
    jumps: Vector[Int],
    inputCursor: Int,
    outputCursor: Int,
    outputEndCursor: Int,
    completed: Boolean) {
    require(jumps.size == providedInputs.size)

    def provideInput: (In, Script[In, Out]) =
      if (noInsPending)
        throw new ScriptException("Script cannot provide more input.")
      else
        (providedInputs(inputCursor), this.copy(inputCursor = inputCursor + 1, outputEndCursor = outputEndCursor + jumps(inputCursor)))

    def consumeOutput(out: Out): Script[In, Out] = {
      if (noOutsPending)
        throw new ScriptException(s"Tried to produce element ${out} but no elements should be produced right now.")
      out should be(expectedOutputs(outputCursor))
      this.copy(outputCursor = outputCursor + 1)
    }

    def complete(): Script[In, Out] = {
      if (finished) copy(completed = true)
      else fail("received onComplete prematurely")
    }

    def finished: Boolean = outputCursor == expectedOutputs.size

    def error(e: Throwable): Script[In, Out] = throw e

    def pendingOuts: Int = outputEndCursor - outputCursor
    def noOutsPending: Boolean = pendingOuts == 0
    def someOutsPending: Boolean = !noOutsPending

    def pendingIns: Int = providedInputs.size - inputCursor
    def noInsPending: Boolean = pendingIns == 0
    def someInsPending: Boolean = !noInsPending

    def debug: String = s"Script(pending=($pendingIns in, $pendingOuts out), remainingIns=${providedInputs.drop(inputCursor).mkString("/")}, remainingOuts=${expectedOutputs.drop(outputCursor).mkString("/")})"
  }

  class ScriptRunner[In, Out](
    op: Flow[In] ⇒ Flow[Out],
    settings: MaterializerSettings,
    script: Script[In, Out],
    maximumOverrun: Int,
    maximumRequest: Int,
    maximumBuffer: Int)(implicit _system: ActorSystem)
    extends ChainSetup(op, settings) {

    var _debugLog = Vector.empty[String]
    var currentScript = script
    var remainingDemand = script.expectedOutputs.size + ThreadLocalRandom.current().nextInt(1, maximumOverrun)
    debugLog(s"starting with remainingDemand=$remainingDemand")
    var pendingRequests = 0
    var outstandingDemand = 0
    var completed = false

    def getNextDemand(): Int = {
      val max = Math.min(remainingDemand, maximumRequest)
      if (max == 1) {
        remainingDemand = 0
        1
      } else {
        val demand = ThreadLocalRandom.current().nextInt(1, max)
        remainingDemand -= demand
        demand
      }
    }

    def debugLog(msg: String): Unit = _debugLog :+= msg

    def requestMore(demand: Int): Unit = {
      debugLog(s"test environment requests $demand")
      downstreamSubscription.requestMore(demand)
      outstandingDemand += demand
    }

    def mayProvideInput: Boolean = currentScript.someInsPending && (pendingRequests > 0) && (currentScript.pendingOuts <= maximumBuffer)
    def mayRequestMore: Boolean = remainingDemand > 0

    def shakeIt(): Boolean = {
      val u = upstream.probe.receiveWhile(1.milliseconds) {
        case RequestMore(_, n) ⇒
          debugLog(s"operation requests $n")
          pendingRequests += n
          true
        case _ ⇒ false // Ignore
      }
      val d = downstream.probe.receiveWhile(1.milliseconds) {
        case OnNext(elem: Out) ⇒
          debugLog(s"operation produces [$elem]")
          if (outstandingDemand == 0) fail("operation produced while there was no demand")
          outstandingDemand -= 1
          currentScript = currentScript.consumeOutput(elem)
          true
        case OnComplete ⇒
          currentScript = currentScript.complete()
          true
        case OnError(e) ⇒
          currentScript = currentScript.error(e)
          true
        case _ ⇒ false // Ignore
      }
      (u ++ d) exists (x ⇒ x)
    }

    def run(): Unit = {

      @tailrec def doRun(idleRounds: Int): Unit = {
        if (idleRounds > 250) fail("too many idle rounds")
        if (!currentScript.completed) {
          val nextIdle = if (shakeIt()) 0 else idleRounds + 1

          val tieBreak = ThreadLocalRandom.current().nextBoolean()
          if (mayProvideInput && (!mayRequestMore || tieBreak)) {
            val (input, nextScript) = currentScript.provideInput
            debugLog(s"test environment produces [${input}]")
            pendingRequests -= 1
            currentScript = nextScript
            upstreamSubscription.sendNext(input)
            doRun(nextIdle)
          } else if (mayRequestMore && (!mayProvideInput || !tieBreak)) {
            requestMore(getNextDemand())
            doRun(nextIdle)
          } else {
            if (currentScript.noInsPending && !completed) {
              debugLog("test environment completes")
              upstreamSubscription.sendComplete()
              completed = true
            }
            doRun(nextIdle)
          }

        }
      }

      try {
        debugLog(s"running $script")
        requestMore(getNextDemand())
        doRun(0)
      } catch {
        case e: Throwable ⇒
          println(_debugLog.mkString("Steps leading to failure:\n", "\n", "\nCurrentScript: " + currentScript.debug))
          throw e
      }

    }

  }

  def runScript[In, Out](script: Script[In, Out], settings: MaterializerSettings, maximumOverrun: Int = 3, maximumRequest: Int = 3, maximumBuffer: Int = 3)(
    op: Flow[In] ⇒ Flow[Out])(implicit system: ActorSystem): Unit = {
    new ScriptRunner(op, settings, script, maximumOverrun, maximumRequest, maximumBuffer).run()
  }

}
