/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.tutorial.first.scala

import akka.actor.{ Actor, PoisonPill }
import Actor._
import java.util.concurrent.CountDownLatch
import akka.routing.Routing.Broadcast
import akka.routing.{ RoutedProps, Routing }
import akka.AkkaApplication

object Pi extends App {

  val app = AkkaApplication()

  calculate(nrOfWorkers = 4, nrOfElements = 10000, nrOfMessages = 10000)

  // ====================
  // ===== Messages =====
  // ====================
  sealed trait PiMessage

  case object Calculate extends PiMessage

  case class Work(start: Int, nrOfElements: Int) extends PiMessage

  case class Result(value: Double) extends PiMessage

  // ==================
  // ===== Worker =====
  // ==================
  class Worker extends Actor {

    // define the work
    def calculatePiFor(start: Int, nrOfElements: Int): Double = {
      var acc = 0.0
      for (i ← start until (start + nrOfElements))
        acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1)
      acc
    }

    def receive = {
      case Work(start, nrOfElements) ⇒
        channel ! Result(calculatePiFor(start, nrOfElements)) // perform the work
    }
  }

  // ==================
  // ===== Master =====
  // ==================
  class Master(nrOfWorkers: Int, nrOfMessages: Int, nrOfElements: Int, latch: CountDownLatch)
    extends Actor {

    var pi: Double = _
    var nrOfResults: Int = _
    var start: Long = _

    // create the workers
    val workers = Vector.fill(nrOfWorkers)(app.actorOf[Worker])

    // wrap them with a load-balancing router
    val router = app.actorOf(RoutedProps().withRoundRobinRouter.withLocalConnections(workers), "pi")

    // message handler
    def receive = {
      case Calculate ⇒
        // schedule work
        for (i ← 0 until nrOfMessages) router ! Work(i * nrOfElements, nrOfElements)

        // send a PoisonPill to all workers telling them to shut down themselves
        router ! Broadcast(PoisonPill)

        // send a PoisonPill to the router, telling him to shut himself down
        router ! PoisonPill

      case Result(value) ⇒
        // handle result from the worker
        pi += value
        nrOfResults += 1
        if (nrOfResults == nrOfMessages) self.stop()
    }

    override def preStart() {
      start = System.currentTimeMillis
    }

    override def postStop() {
      // tell the world that the calculation is complete
      println(
        "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis"
          .format(pi, (System.currentTimeMillis - start)))
      latch.countDown()
    }
  }

  // ==================
  // ===== Run it =====
  // ==================
  def calculate(nrOfWorkers: Int, nrOfElements: Int, nrOfMessages: Int) {

    // this latch is only plumbing to know when the calculation is completed
    val latch = new CountDownLatch(1)

    // create the master
    val master = app.actorOf(new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch))

    // start the calculation
    master ! Calculate

    // wait for master to shut down
    latch.await()
  }
}
