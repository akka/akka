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
  val lengthOfCalculationRange = 10000

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
  class Master(nrOfMessages: Int, latch: CountDownLatch) extends Actor {
    var pi: Double = _
    var count: Int = _
    var start: Long = _

    def receive = {
      case Result(value) =>
        pi += value
        count += 1
        if (count == nrOfMessages) self.stop
    }

    override def preStart = start = now

    override def postStop = {
      EventHandler.info(this, "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis".format(pi, (now - start)))
      Actor.registry.shutdownAll // shut down all workers
      latch.countDown
    }
  }

  def calculate = {
    val latch = new CountDownLatch(1)

    // create the master
    val master = actorOf(new Master(nrOfMessages, latch)).start

    // the master ref is also the 'implicit sender' that the workers should reply to
    implicit val replyTo = Option(master)

    // create the workers
    val workers = new Array[ActorRef](nrOfWorkers)
    for (i <- 0 until nrOfWorkers) workers(i) = actorOf[Worker].start

    // wrap them with a load-balancing router
    val router = Routing.loadBalancerActor(CyclicIterator(workers)).start

    val fun = (x: Int) => (for (k <- (x * lengthOfCalculationRange) to ((x + 1) * lengthOfCalculationRange - 1)) yield (4 * math.pow(-1, k) / (2 * k + 1))).sum

    // schedule work
    for (arg <- 0 until nrOfMessages) router ! Work(arg, fun)

    latch.await
  }
}

