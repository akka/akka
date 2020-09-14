package docs.stream.operators.sourceorflow

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * 
 */
object CommonMapAsync{
  case class Event(sequenceNumber: Int)
  case class EventProcessingRequest(evt: Event, replyTo: ActorRef[Int])

  val guardian = Behaviors.receiveMessage[EventProcessingRequest] {
    case EventProcessingRequest(evt, replyTo) =>
      TimeUnit.MILLISECONDS.sleep(500)
      replyTo.tell(evt.sequenceNumber)
      Behaviors.same
  }

  implicit val sys =
    ActorSystem.apply[EventProcessingRequest](guardian, "mapAsync-stream")
  implicit val exCtx = sys.executionContext
  implicit val timeout: Timeout = 3.seconds

  val events: Source[Event, NotUsed] =
    Source
      .fromIterator(() => Iterator.from(1))
      .throttle(1, 50.millis)
      .map { in =>
        Event(in)
      }

  def eventHandler(in: Event): Future[Int] = {
    println(s"Processing event number $in...")
    val result =
      if (in.sequenceNumber % 5 == 0) {
        import akka.actor.typed.scaladsl.AskPattern._
        sys.ask { replyTo =>
          EventProcessingRequest(in, replyTo)
        }
      } else {
        Future.successful(in.sequenceNumber)
      }
    result
  }

}

object MapAsyncStrictOrder extends App {
 import CommonMapAsync._

  // #mapasync-strict-order
  events
    .mapAsync(1) { in =>
      eventHandler(in)
    }
  // #mapasync-strict-order
    .map { in =>
      println(s"`mapAsync` emitted event number: $in")
    }
    .runWith(Sink.ignore)


}

object MapAsync extends App {
 import CommonMapAsync._

  // #mapasync-concurrent
  events
    .mapAsync(3) { in =>
      eventHandler(in)
    }
    .map { in =>
      println(s"`mapAsync` emitted event number: $in")
    }
  // #mapasync-concurrent
    .runWith(Sink.ignore)


}

object MapAsyncUnordered extends App{
 import CommonMapAsync._

  // #mapasyncunordered
  events
    .mapAsyncUnordered(3) { in =>
      eventHandler(in)
    }
    .map { in =>
      println(s"`mapAsyncUnordered` emitted event number: $in")
    }
  // #mapasyncunordered
    .runWith(Sink.ignore)


}
