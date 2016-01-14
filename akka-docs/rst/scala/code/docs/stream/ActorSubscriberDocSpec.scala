/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.routing.ActorRefRoutee
import akka.routing.RoundRobinRoutingLogic
import akka.routing.Router
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorSubscriber
import akka.stream.actor.ActorSubscriberMessage
import akka.stream.actor.MaxInFlightRequestStrategy
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.AkkaSpec

object ActorSubscriberDocSpec {
  //#worker-pool
  object WorkerPool {
    case class Msg(id: Int, replyTo: ActorRef)
    case class Work(id: Int)
    case class Reply(id: Int)
    case class Done(id: Int)

    def props: Props = Props(new WorkerPool)
  }

  class WorkerPool extends ActorSubscriber {
    import WorkerPool._
    import ActorSubscriberMessage._

    val MaxQueueSize = 10
    var queue = Map.empty[Int, ActorRef]

    val router = {
      val routees = Vector.fill(3) {
        ActorRefRoutee(context.actorOf(Props[Worker]))
      }
      Router(RoundRobinRoutingLogic(), routees)
    }

    override val requestStrategy = new MaxInFlightRequestStrategy(max = MaxQueueSize) {
      override def inFlightInternally: Int = queue.size
    }

    def receive = {
      case OnNext(Msg(id, replyTo)) =>
        queue += (id -> replyTo)
        assert(queue.size <= MaxQueueSize, s"queued too many: ${queue.size}")
        router.route(Work(id), self)
      case Reply(id) =>
        queue(id) ! Done(id)
        queue -= id
    }
  }

  class Worker extends Actor {
    import WorkerPool._
    def receive = {
      case Work(id) =>
        // ...
        sender() ! Reply(id)
    }
  }
  //#worker-pool

}

class ActorSubscriberDocSpec extends AkkaSpec {
  import ActorSubscriberDocSpec._

  implicit val materializer = ActorMaterializer()

  "illustrate usage of ActorSubscriber" in {
    val replyTo = testActor

    //#actor-subscriber-usage
    val N = 117
    Source(1 to N).map(WorkerPool.Msg(_, replyTo))
      .runWith(Sink.actorSubscriber(WorkerPool.props))
    //#actor-subscriber-usage

    receiveN(N).toSet should be((1 to N).map(WorkerPool.Done).toSet)
  }

}
