/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.tutorial.sbt.pi

import akka.actor.{Actor, ActorRef}
import Actor._
import akka.routing.{Routing, CyclicIterator}
import akka.event.EventHandler
import akka.dispatch.Dispatchers

import System.{currentTimeMillis => now}
import java.util.concurrent.CountDownLatch

object Main extends App {
  Pi.calculate
}

/*
  Pi estimate:    3.1415926435897883

  === 8 workers (with custom dispatcher 4/4)
  Calculation time:   5163 millis

  === 8 workers (with default dispatcher)
  Calculation time:   6789 millis

  === 4 workers
  Calculation time:   5438 millis

  === 2 workers
  Calculation time:   6002 millis

  === 1 workers
  Calculation time:   8173 millis
*/
object Pi  {
  val nrOfWorkers              = 4
  val nrOfMessages             = 10000
  val nrOfElements = 10000

  // ===== Messages =====
  sealed trait PiMessage
  case class Work(arg: Int, fun: (Int) => Double) extends PiMessage
  case class Result(value: Double) extends PiMessage

  // ===== Worker =====
  class Worker extends Actor {
    def receive = {
      case Work(arg, fun) => self.reply(Result(fun(arg)))
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

    def receive = {
      case Calculate(nrOfMessages, nrOfElements) =>
        // define the work
        val fun = (i: Int) => {
          val range = (i * nrOfElements) to ((i + 1) * nrOfElements - 1)
          val results = for (j <- range) yield (4 * math.pow(-1, j) / (2 * j + 1))
          results.sum
        }
        // schedule work
        for (arg <- 0 until nrOfMessages) router ! Work(arg, fun)

        // send a PoisonPill to all workers telling them to shut down themselves
        router broadcast PoisonPill

      case Result(value) =>
        pi += value
        nrOfResults += 1
        if (nrOfResults == nrOfMessages) self.stop
    }

    override def preStart = start = now

    override def postStop = {
      EventHandler.info(this, "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis".format(pi, (now - start)))
      latch.nrOfResultsDown
    }
  }

  def calculate = {
    val latch = new nrOfResultsDownLatch(1)

    // create the master
    val master = actorOf(new Master(latch)).start

    // start the calculation
    master ! Calculate(nrOfMessages, nrOfElements)

    // wait for master to shut down
    latch.await
  }
}

