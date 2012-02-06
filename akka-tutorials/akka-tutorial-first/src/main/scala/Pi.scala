/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.tutorial.first.scala

//#imports
import akka.actor._
import akka.routing.RoundRobinRouter
import akka.util.Duration
import akka.util.duration._
//#imports

//#app
object Pi extends App {

  //#actors-and-messages
  //#messages
  sealed trait PiMessage
  case object Calculate extends PiMessage
  case class Work(start: Int, nrOfElements: Int) extends PiMessage
  case class Result(value: Double) extends PiMessage
  case class PiApproximation(pi: Double, duration: Duration)
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
  class Master(nrOfMessages: Int, nrOfElements: Int, listener: ActorRef)
    extends Actor {

    var pi: Double = _
    var nrOfResults: Int = _
    val start: Long = System.currentTimeMillis

    //#create-router
    val workerRouter = context.actorOf(
      Props[Worker].withRouter(RoundRobinRouter(4)), name = "workerRouter")
    //#create-router

    //#master-receive
    def receive = {
      //#handle-messages
      case Calculate ⇒
        for (i ← 0 until nrOfMessages) workerRouter ! Work(i * nrOfElements, nrOfElements)
      case Result(value) ⇒
        pi += value
        nrOfResults += 1
        if (nrOfResults == nrOfMessages) {
          // Send the result to the listener
          listener ! PiApproximation(pi, duration = (System.currentTimeMillis - start).millis)
          // Stops this actor and all its supervised children
          context.stop(self)
        }
      //#handle-messages
    }
    //#master-receive

  }
  //#master

  //#result-listener
  class Listener extends Actor {
    def receive = {
      case PiApproximation(pi, duration) ⇒
        println("\n\tPi approximation: \t\t%s\n\tCalculation time: \t%s"
          .format(pi, duration))
        context.system.shutdown()
    }
  }
  //#result-listener

  //#actors-and-messages

  // Create an Akka system
  val system = ActorSystem("PiSystem")

  // extract configuration items
  val config = system.settings.config
  val nrOfMessages = config.getInt("pi.messages")
  val nrOfElements = config.getInt("pi.elements")

  // create the result listener, which will print the result and shutdown the system
  val listener = system.actorOf(Props[Listener], name = "listener")

  // create the master
  val master = system.actorOf(
    Props(new Master(nrOfMessages, nrOfElements, listener)),
    name = "master")

  // start the calculation
  master ! Calculate
}
//#app
