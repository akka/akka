/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.tutorial.first.scala

//#imports
import java.util.concurrent.CountDownLatch
import akka.actor._
import akka.routing._
//#imports

//#app
object Pi extends App {

  calculate(nrOfWorkers = 4, nrOfElements = 10000, nrOfMessages = 10000)

  //#actors-and-messages
  //#messages
  sealed trait PiMessage
  case object Calculate extends PiMessage
  case class Work(start: Int, nrOfElements: Int) extends PiMessage
  case class Result(value: Double) extends PiMessage
  //#messages

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
        sender ! Result(calculatePiFor(start, nrOfElements)) // perform the work
    }
  }
  //#worker

  //#master
  class Master(
    nrOfWorkers: Int, nrOfMessages: Int, nrOfElements: Int, latch: CountDownLatch)
    extends Actor {

    var pi: Double = _
    var nrOfResults: Int = _
    var start: Long = _

    //#create-router
    val router = context.actorOf(
      Props[Worker].withRouter(RoundRobinRouter(nrOfWorkers)), "pi")
    //#create-router

    //#master-receive
    def receive = {
      //#handle-messages
      case Calculate ⇒
        for (i ← 0 until nrOfMessages) router ! Work(i * nrOfElements, nrOfElements)
      case Result(value) ⇒
        pi += value
        nrOfResults += 1
        // Stops this actor and all its supervised children
        if (nrOfResults == nrOfMessages) context.stop(self)
      //#handle-messages
    }
    //#master-receive

    override def preStart() {
      start = System.currentTimeMillis
    }

    override def postStop() {
      println("\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis"
        .format(pi, (System.currentTimeMillis - start)))
      latch.countDown()
    }
  }
  //#master
  //#actors-and-messages

  def calculate(nrOfWorkers: Int, nrOfElements: Int, nrOfMessages: Int) {
    // Create an Akka system
    val system = ActorSystem("PiSystem")

    // this latch is only plumbing to know when the calculation is completed
    val latch = new CountDownLatch(1)

    // create the master
    val master = system.actorOf(Props(new Master(
      nrOfWorkers, nrOfMessages, nrOfElements, latch)),
      "master")

    // start the calculation
    master ! Calculate

    // wait for master to shut down
    latch.await()

    // Shut down the system
    system.shutdown()
  }
}
//#app
