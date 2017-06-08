package akka.actor

import akka.testkit.TestProbe

import scala.concurrent.duration.Duration
import scala.concurrent.duration._

object BenchmarkActors {

  val timeout = 15.seconds
  case object Message
  case object Stop

  class PingPong(val messages: Int) extends Actor {
    var left = messages / 2
    def receive = {
      case Message =>

        if (left <= 1)
          context stop self

        sender() ! Message
        left -= 1
    }
  }

  object PingPong {
    def props(messages: Int) = Props(new PingPong(messages))
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

  def startPingPongActorPairs(messagesPerPair: Int, numPairs: Int, dispatcher: String)(implicit system: ActorSystem): Vector[(ActorRef, ActorRef)] = {
    val fullPathToDispatcher = "akka.actor." + dispatcher
    for {
      i <- (1 to numPairs).toVector
    } yield {
      val ping = system.actorOf(PingPong.props(messagesPerPair).withDispatcher(fullPathToDispatcher))
      val pong = system.actorOf(PingPong.props(messagesPerPair).withDispatcher(fullPathToDispatcher))
      (ping, pong)
    }
  }

  def stopPingPongActorPairs(refs: Vector[(ActorRef, ActorRef)], timeout: Duration = 15.seconds)(implicit system: ActorSystem): Unit = {
    if (refs ne null) {
      refs.foreach {
        case (ping, pong) =>
          system.stop(ping)
          system.stop(pong)
      }
      awaitTerminatedPingPongActorPairs(refs, timeout)
    }
  }

  def initiatePingPongForPairs(refs: Vector[(ActorRef, ActorRef)], inFlight: Int): Unit = {
    for {
      (ping, pong) <- refs
      _ <- 1 to inFlight
    } {
      ping.tell(Message, pong)
    }
  }

  def awaitTerminatedPingPongActorPairs(refs: Vector[(ActorRef, ActorRef)], timeout: Duration = 15.seconds)(implicit system: ActorSystem): Unit = {
    if (refs ne null) refs.foreach {
      case (ping, pong) =>
        val p = TestProbe()(system)
        p.watch(ping)
        p.expectTerminated(ping, timeout)
        p.watch(pong)
        p.expectTerminated(pong, timeout)
    }
  }

}

