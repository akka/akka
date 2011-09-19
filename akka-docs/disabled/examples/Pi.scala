//#imports
package akka.tutorial.scala.first

import _root_.akka.routing.{RoutedProps, Routing, CyclicIterator}
import akka.actor.{Actor, PoisonPill}
import Actor._
import Routing._

import System.{currentTimeMillis => now}
import java.util.concurrent.CountDownLatch
//#imports

//#app
object Pi extends App {

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

    //#calculate-pi
    def calculatePiFor(start: Int, nrOfElements: Int): Double = {
      var acc = 0.0
      for (i <- start until (start + nrOfElements))
        acc += 4.0 * math.pow(-1, i) / (2 * i + 1)
      acc
    }
    //#calculate-pi

    def receive = {
      case Work(start, nrOfElements) =>
        self reply Result(calculatePiFor(start, nrOfElements)) // perform the work
    }
  }
  //#worker

  // ==================
  // ===== Master =====
  // ==================
  //#master
  class Master(
    nrOfWorkers: Int, nrOfMessages: Int, nrOfElements: Int, latch: CountDownLatch)
    extends Actor {

    var pi: Double = _
    var nrOfResults: Int = _
    var start: Long = _

    //#create-workers
    // create the workers
    val workers = Vector.fill(nrOfWorkers)(actorOf[Worker])

    // wrap them with a load-balancing router
    val router = Routing.actorOf(RoutedProps().withRoundRobinRouter.withConnections(workers), "pi")

    loadBalancerActor(CyclicIterator(workers))
    //#create-workers

    //#master-receive
    // message handler
    def receive = {
      //#message-handling
      case Calculate =>
        // schedule work
        for (i <- 0 until nrOfMessages) router ! Work(i * nrOfElements, nrOfElements)

        // send a PoisonPill to all workers telling them to shut down themselves
        router ! Broadcast(PoisonPill)

        // send a PoisonPill to the router, telling him to shut himself down
        router ! PoisonPill

      case Result(value) =>
        // handle result from the worker
        pi += value
        nrOfResults += 1
        if (nrOfResults == nrOfMessages) self.stop()
      //#message-handling
    }
    //#master-receive

    override def preStart() {
      start = now
    }

    override def postStop() {
      // tell the world that the calculation is complete
      println(
        "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis"
          .format(pi, (now - start)))
      latch.countDown()
    }
  }
  //#master
  //#actors-and-messages

  // ==================
  // ===== Run it =====
  // ==================
  def calculate(nrOfWorkers: Int, nrOfElements: Int, nrOfMessages: Int) {

    // this latch is only plumbing to know when the calculation is completed
    val latch = new CountDownLatch(1)

    // create the master
    val master = actorOf(
      new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch))

    // start the calculation
    master ! Calculate

    // wait for master to shut down
    latch.await()
  }
}
//#app

