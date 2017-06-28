/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration._

object BenchmarkActors {

  val timeout = 30.seconds

  case object Message
  case object Stop

  class PingPong(val messages: Int, latch: CountDownLatch) extends Actor {
    var left = messages / 2
    def receive = {
      case Message =>

        if (left == 0) {
          latch.countDown()
          context stop self
        }

        sender() ! Message
        left -= 1
    }
  }

  object PingPong {
    def props(messages: Int, latch: CountDownLatch) = Props(new PingPong(messages, latch))
  }

  class Pipe(next: Option[ActorRef]) extends Actor {
    def receive = {
      case Message =>
        if (next.isDefined) next.get forward Message
      case Stop =>
        context stop self
        if (next.isDefined) next.get forward Stop
    }
  }

  object Pipe {
    def props(next: Option[ActorRef]) = Props(new Pipe(next))
  }

  private def startPingPongActorPairs(messagesPerPair: Int, numPairs: Int, dispatcher: String)(implicit system: ActorSystem) = {
    val fullPathToDispatcher = "akka.actor." + dispatcher
    val latch = new CountDownLatch(numPairs * 2)
    val actors = for {
      i <- (1 to numPairs).toVector
    } yield {
      val ping = system.actorOf(PingPong.props(messagesPerPair, latch).withDispatcher(fullPathToDispatcher))
      val pong = system.actorOf(PingPong.props(messagesPerPair, latch).withDispatcher(fullPathToDispatcher))
      (ping, pong)
    }
    (actors, latch)
  }

  private def initiatePingPongForPairs(refs: Vector[(ActorRef, ActorRef)], inFlight: Int) = {
    for {
      (ping, pong) <- refs
      _ <- 1 to inFlight
    } {
      ping.tell(Message, pong)
    }
  }

  def printProgress(totalMessages: Long, numActors: Int, startNanoTime: Long) = {
    val durationMicros = (System.nanoTime() - startNanoTime) / 1000
    println(f"  $totalMessages messages by $numActors actors took ${durationMicros / 1000} ms, " +
      f"${totalMessages.toDouble / durationMicros}%,.2f M msg/s")
  }

  def requireRightNumberOfCores(numCores: Int) =
    require(
      Runtime.getRuntime.availableProcessors == numCores,
      s"Update the cores constant to ${Runtime.getRuntime.availableProcessors}"
    )

  def benchmarkPingPongActors(numMessagesPerActorPair: Int, numActors: Int, dispatcher: String, throughPut: Int, shutdownTimeout: Duration)(implicit system: ActorSystem): Unit = {
    val numPairs = numActors / 2
    val totalNumMessages = numPairs * numMessagesPerActorPair
    val (actors, latch) = startPingPongActorPairs(numMessagesPerActorPair, numPairs, dispatcher)
    val startNanoTime = System.nanoTime()
    initiatePingPongForPairs(actors, inFlight = throughPut * 2)
    latch.await(shutdownTimeout.toSeconds, TimeUnit.SECONDS)
    printProgress(totalNumMessages, numActors, startNanoTime)
  }

  def tearDownSystem()(implicit system: ActorSystem): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, timeout)
  }

}

