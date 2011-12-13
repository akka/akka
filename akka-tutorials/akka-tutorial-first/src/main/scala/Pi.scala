/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.tutorial.first.scala

import java.util.concurrent.CountDownLatch
import akka.actor._
import akka.routing._
import com.typesafe.config.{ ConfigFactory, Config }

object Pi extends App {

  // Initiate the calculation
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
        sender ! Result(calculatePiFor(start, nrOfElements)) // perform the work
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

    // create a round robin router for the workers
    val router = context.actorOf(Props(new Worker).withRouter(RoundRobinRouter(nrOfInstances = 5)), "pi")

    // message handler
    def receive = {
      case Calculate ⇒
        // schedule work
        for (i ← 0 until nrOfMessages) router ! Work(i * nrOfElements, nrOfElements)
      case Result(value) ⇒
        // handle result from the worker
        pi += value
        nrOfResults += 1

        // Stop this actor and all its supervised children
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
    val system = ActorSystem("PiSystem")

    // this latch is only plumbing to know when the calculation is completed
    val latch = new CountDownLatch(1)

    // create the master
    val master = system.actorOf(Props(new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch)), "master")

    // start the calculation
    master ! Calculate

    // wait for master to shut down
    latch.await()

    // Shut down the system
    system.stop()
  }
}
