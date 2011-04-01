/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.tutorial.sbt.pi

import akka.actor.{Actor, ActorRef, PoisonPill}
import Actor._
import akka.routing.{Routing, CyclicIterator}
import Routing._
import akka.event.EventHandler
import akka.dispatch.Dispatchers

import System.{currentTimeMillis => now}
import java.util.concurrent.CountDownLatch

object Main extends App {
  Pi.calculate
}

object Pi  {
  val nrOfWorkers  = 4
  val nrOfMessages = 10000
  val nrOfElements = 10000

  // ===== Messages =====
  sealed trait PiMessage
  case class Calculate(nrOfMessages: Int, nrOfElements: Int) extends PiMessage
  case class Work(arg: Int, fun: (Int) => Double) extends PiMessage
  case class Result(value: Double) extends PiMessage

  // ===== Worker =====
  class Worker extends Actor {
    def receive = {
      case Work(arg, fun) => self reply Result(fun(arg))
    }
  }

  // ===== Master =====
  class Master(latch: CountDownLatch) extends Actor {
    var pi: Double = _
    var nrOfResults: Int = _
    var start: Long = _

    // create the workers
    val workers = {
      val ws = new Array[ActorRef](nrOfWorkers)
      for (i <- 0 until nrOfWorkers) ws(i) = actorOf[Worker].start
      ws
    }

    // wrap them with a load-balancing router
    val router = Routing.loadBalancerActor(CyclicIterator(workers)).start

    // define the work
    val algorithm = (i: Int) => {
      val range = (i * nrOfElements) to ((i + 1) * nrOfElements - 1)
      val results = for (j <- range) yield (4 * math.pow(-1, j) / (2 * j + 1))
      results.sum
    }

    def receive = {
      case Calculate(nrOfMessages, nrOfElements) =>
        // schedule work
        for (arg <- 0 until nrOfMessages) router ! Work(arg, algorithm)

        // send a PoisonPill to all workers telling them to shut down themselves
        router ! Broadcast(PoisonPill)

      case Result(value) =>
        pi += value
        nrOfResults += 1
        if (nrOfResults == nrOfMessages) self.stop
    }

    override def preStart = start = now

    override def postStop = {
      EventHandler.info(this, "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis".format(pi, (now - start)))
      latch.countDown
    }
  }

  def calculate = {
    val latch = new CountDownLatch(1)

    // create the master
    val master = actorOf(new Master(latch)).start

    // start the calculation
    master ! Calculate(nrOfMessages, nrOfElements)

    // wait for master to shut down
    latch.await
  }
}

