/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.tutorial.sbt.pi

import akka.actor.{Actor, ActorRef, PoisonPill}
import Actor._
import akka.routing.{Routing, CyclicIterator}
import Routing._
import akka.dispatch.Dispatchers

import System.{currentTimeMillis => now}
import java.util.concurrent.CountDownLatch

/**
 * Sample for Akka, SBT an Scala tutorial.
 * <p/>
 * Calculates Pi.
 * <p/>
 * Run it in SBT:
 * <pre>
 *   $ sbt
 *   > update
 *   > console
 *   > akka.tutorial.sbt.pi.Pi.calculate
 *   > ...
 *   > :quit
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Pi  {

  // ====================
  // ===== Messages =====
  // ====================
  sealed trait PiMessage
  case object Calculate extends PiMessage
  case class Work(arg: Int, nrOfElements: Int) extends PiMessage
  case class Result(value: Double) extends PiMessage

  // ==================
  // ===== Worker =====
  // ==================
  class Worker() extends Actor {
    // define the work
    val calculatePiFor = (arg: Int, nrOfElements: Int) => {
      val range = (arg * nrOfElements) to ((arg + 1) * nrOfElements - 1)
      range map (j => 4 * math.pow(-1, j) / (2 * j + 1)) sum
    }

    def receive = {
      case Work(arg, nrOfElements) =>
        self reply Result(calculatePiFor(arg, nrOfElements)) // perform the work
    }
  }

  // ==================
  // ===== Master =====
  // ==================
  class Master(nrOfWorkers: Int, nrOfMessages: Int, nrOfElements: Int, latch: CountDownLatch) extends Actor {
    var pi: Double = _
    var nrOfResults: Int = _
    var start: Long = _

    // create the workers
    val workers = Vector.fill(nrOfWorkers)(actorOf[Worker].start)

    // wrap them with a load-balancing router
    val router = Routing.loadBalancerActor(CyclicIterator(workers)).start

    // message handler
    def receive = {
      case Calculate =>
        // schedule work
        for (arg <- 0 until nrOfMessages) router ! Work(arg, nrOfElements)

        // send a PoisonPill to all workers telling them to shut down themselves
        router ! Broadcast(PoisonPill)

        // send a PoisonPill to the router, telling him to shut himself down
        router ! PoisonPill

      case Result(value) =>
        // handle result from the worker
        pi += value
        nrOfResults += 1
        if (nrOfResults == nrOfMessages) self.stop
    }

    override def preStart = start = now

    override def postStop = {
      // tell the world that the calculation is complete
      println("\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis".format(pi, (now - start)))
      latch.countDown
    }
  }

  // ==================
  // ===== Run it =====
  // ==================
  def calculate = {
    val nrOfWorkers  = 4
    val nrOfMessages = 10000
    val nrOfElements = 10000

    // this latch is only plumbing to know when the calculation is completed
    val latch = new CountDownLatch(1)

    // create the master
    val master = actorOf(new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch)).start

    // start the calculation
    master ! Calculate

    // wait for master to shut down
    latch.await
  }
}
