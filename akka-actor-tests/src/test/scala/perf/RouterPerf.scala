package perf

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.routing.ConsistentHashingRouter
import akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope
import scala.util.Random
import akka.routing.Broadcast
import akka.actor.ActorLogging
import scala.concurrent.util.Duration
import akka.routing.RoundRobinRouter

object RouterPerf extends App {
  val system = ActorSystem("PerfApp")
  var perf = new RouterPerf(system)
  //  Thread.sleep(20000) // hook up profiler here
  perf.run()
}

class RouterPerf(system: ActorSystem) {

  def run(): Unit = {
    // nbrMessages = 10000000
    val sender = system.actorOf(Props(new Sender(
      nbrMessages = 10000000, nbrRoutees = 10, nbrIterations = 10)), name = "sender")
    sender ! "start"
  }

}

class Sender(nbrMessages: Int, nbrRoutees: Int, nbrIterations: Int) extends Actor with ActorLogging {
  val router = context.actorOf(Props[Destination].withRouter(ConsistentHashingRouter(nbrRoutees,
    virtualNodesFactor = 10)), "router")
  //  val router = context.actorOf(Props[Destination].withRouter(RoundRobinRouter(nbrRoutees)), "router")
  val rnd = new Random
  val messages = Vector.fill(1000)(ConsistentHashableEnvelope("msg", rnd.nextString(10)))
  var startTime = 0L
  var doneCounter = 0
  var iterationCounter = 0

  def receive = {
    case "start" ⇒
      iterationCounter += 1
      doneCounter = 0
      startTime = System.nanoTime
      val messgesSize = messages.size
      for (n ← 1 to nbrMessages) { router ! messages(n % messgesSize) }
      router ! Broadcast("done")

    case "done" ⇒
      doneCounter += 1
      if (doneCounter == nbrRoutees) {
        val duration = Duration.fromNanos(System.nanoTime - startTime)
        val mps = (nbrMessages.toDouble * 1000 / duration.toMillis).toInt
        //        log.info("Processed [{}] messages in [{} millis], i.e. [{}] msg/s",
        //          nbrMessages, duration.toMillis, mps)
        println("Processed [%s] messages in [%s millis], i.e. [%s] msg/s".format(
          nbrMessages, duration.toMillis, mps))
        if (iterationCounter < nbrIterations)
          self ! "start"
        else
          context.system.shutdown()
      }
  }
}

class Destination extends Actor with ActorLogging {
  var count = 0
  def receive = {
    case "done" ⇒
      log.info("Handled [{}] messages", count)
      count = 0
      sender ! "done"
    case msg ⇒ count += 1

  }
}