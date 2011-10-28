/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.tutorial.second

import akka.actor.Actor._
import akka.event.EventHandler
import System.{ currentTimeMillis ⇒ now }
import akka.routing.Routing.Broadcast
import akka.routing._
import akka.AkkaApplication
import akka.actor.{ ActorRef, Timeout, Actor, PoisonPill }

object Pi extends App {

  val app = AkkaApplication()

  calculate(nrOfWorkers = 4, nrOfElements = 10000, nrOfMessages = 10000)

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
    val calculatePiFor = (arg: Int, nrOfElements: Int) ⇒ {
      val range = (arg * nrOfElements) to ((arg + 1) * nrOfElements - 1)
      var acc = 0.0D
      range foreach (i ⇒ acc += 4 * math.pow(-1, i) / (2 * i + 1))
      acc
      //range map (j => 4 * math.pow(-1, j) / (2 * j + 1)) sum
    }

    def receive = {
      case Work(arg, nrOfElements) ⇒ sender ! Result(calculatePiFor(arg, nrOfElements)) // perform the work
    }
  }

  // ==================
  // ===== Master =====
  // ==================
  case class Master(nrOfWorkers: Int, nrOfElements: Int, nrOfMessages: Int) extends Actor {
    var pi: Double = _
    var nrOfResults: Int = _

    // create the workers
    val workers = Vector.fill(nrOfWorkers)(app.actorOf[Worker])

    // wrap them with a load-balancing router
    val router = app.actorOf(RoutedProps(
      routerFactory = () ⇒ new RoundRobinRouter,
      connectionManager = new LocalConnectionManager(workers)), "pi")

    // phase 1, can accept a Calculate message
    def scatter: Receive = {
      case Calculate ⇒
        // schedule work
        for (arg ← 0 until nrOfMessages) router ! Work(arg, nrOfElements)

        //Assume the gathering behavior
        this become gather(sender)
    }

    // phase 2, aggregate the results of the Calculation
    def gather(recipient: ActorRef): Receive = {
      case Result(value) ⇒
        // handle result from the worker
        pi += value
        nrOfResults += 1
        if (nrOfResults == nrOfMessages) {
          // send the pi result back to the guy who started the calculation
          recipient ! pi
          // shut ourselves down, we're done
          self.stop()
        }
    }

    // message handler starts at the scattering behavior
    def receive = scatter

    // when we are stopped, stop our team of workers and our router
    override def postStop() {
      // send a PoisonPill to all workers telling them to shut down themselves
      router ! Broadcast(PoisonPill)
      // send a PoisonPill to the router, telling him to shut himself down
      router ! PoisonPill
    }
  }

  // ==================
  // ===== Run it =====
  // ==================
  def calculate(nrOfWorkers: Int, nrOfElements: Int, nrOfMessages: Int) {
    // create the master
    val master = app.actorOf(new Master(nrOfWorkers, nrOfElements, nrOfMessages))

    //start the calculation
    val start = now

    //send calculate message
    master.?(Calculate, Timeout(60000)).
      await.resultOrException match { //wait for the result, with a 60 seconds timeout
        case Some(pi) ⇒
          app.eventHandler.info(this, "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis".format(pi, (now - start)))
        case None ⇒
          app.eventHandler.error(this, "Pi calculation did not complete within the timeout.")
      }
  }
}
