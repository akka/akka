/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

//#imports
package akka.tutorial.first.scala

import java.util.concurrent.CountDownLatch
import akka.routing.{RoutedActorRef, LocalConnectionManager, RoundRobinRouter, RoutedProps}
import akka.actor.{Actor, ActorSystem}
//#imports

//#app
object Pi extends App {

  // Initiate the calculation
  calculate(nrOfWorkers = 4, nrOfElements = 10000, nrOfMessages = 10000)

  //#actors-and-messages
  // ====================
  // ===== Messages =====
  // ====================
  //#messages
  sealed trait PiMessage

  case object Calculate extends PiMessage

  case class Work(start: Int, nrOfElements: Int) extends PiMessage

  case class Result(value: Double) extends PiMessage
  //#messages

  // ==================
  // ===== Worker =====
  // ==================
  //#worker
  class Worker extends Actor {

    //#calculatePiFor
    def calculatePiFor(start: Int, nrOfElements: Int): Double = {
      var acc = 0.0
      for (i ← start until (start + nrOfElements))
        acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1)
      acc
    }
    //#calculatePiFor

    def receive = {
      case Work(start, nrOfElements) ⇒
        sender ! Result(calculatePiFor(start, nrOfElements))
    }
  }
  //#worker

  // ==================
  // ===== Master =====
  // ==================
  //#master
  class Master(nrOfWorkers: Int,
               nrOfMessages: Int,
               nrOfElements: Int,
               latch: CountDownLatch) extends Actor {

    var pi: Double = _
    var nrOfResults: Int = _
    var start: Long = _

    // create the workers
    //#create-workers
    val workers = Vector.fill(nrOfWorkers)(system.actorOf[Worker])

    // wrap them with a load-balancing router
    val props = RoutedProps(
      routerFactory = () ⇒ new RoundRobinRouter,
      connectionManager = new LocalConnectionManager(workers))

    val router = new RoutedActorRef(system, props, self, "pi")
    //#create-workers

    // message handler
    //#master-receive
    def receive = {
      //#handle-messages
      case Calculate ⇒
        // schedule work
        for (i ← 0 until nrOfMessages)
          router ! Work(i * nrOfElements, nrOfElements)
      case Result(value) ⇒
        // handle result from the worker
        pi += value
        nrOfResults += 1
        // stop this actor and all its supervised children
        if (nrOfResults == nrOfMessages) self.stop()
      //#handle-messages
    }
    //#master-receive

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
  //#master
  //#actors-and-messages

  // ==================
  // ===== Run it =====
  // ==================
  def calculate(nrOfWorkers: Int, nrOfElements: Int, nrOfMessages: Int) {
    val system = ActorSystem()

    // this latch is only plumbing to know when the calculation is completed
    val latch = new CountDownLatch(1)

    // create the master
    val master = system.actorOf(new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch))

    // start the calculation
    master ! Calculate

    // wait for master to shut down
    latch.await()

    // Shut down the system
    system.stop()
  }
}
//#app