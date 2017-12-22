/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed

import akka.actor.typed.scaladsl.Actor

import scala.concurrent._
import akka.testkit.typed.TestKit
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

object WatchSpec {
  case object Stop

  val terminatorBehavior =
    Actor.immutable[Stop.type] {
      case (_, `Stop`) ⇒ Actor.stopped
    }

  sealed trait Message
  case object CustomTerminationMessage extends Message
  case class StartWatchingWith(watchee: ActorRef[Stop.type], msg: CustomTerminationMessage.type) extends Message
}

class WatchSpec extends TestKit("WordSpec")
  with WordSpecLike with BeforeAndAfterAll with Matchers with ScalaFutures {

  import WatchSpec._

  override protected def afterAll(): Unit = shutdown()

  "Actor monitoring" must {
    "get notified of actor termination" in {
      case class StartWatching(watchee: ActorRef[Stop.type])
      val terminator = systemActor(terminatorBehavior)
      val receivedTerminationSignal: Promise[ActorRef[Nothing]] = Promise()

      val watcher = systemActor(Actor.immutable[StartWatching] {
        case (ctx, StartWatching(watchee)) ⇒
          ctx.watch(watchee)
          Actor.same
      }.onSignal {
        case (_, Terminated(stopped)) ⇒
          receivedTerminationSignal.success(stopped)
          Actor.stopped
      })

      watcher ! StartWatching(terminator)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual terminator
    }

    "get notified of actor termination with a custom message" in {
      val terminator = systemActor(terminatorBehavior)
      val receivedTerminationSignal: Promise[Message] = Promise()

      val watcher = systemActor(Actor.immutable[Message] {
        case (ctx, StartWatchingWith(watchee, msg)) ⇒
          ctx.watchWith(watchee, msg)
          Actor.same
        case (_, msg) ⇒
          receivedTerminationSignal.success(msg)
          Actor.stopped
      })

      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)
      terminator ! Stop

      receivedTerminationSignal.future.futureValue shouldEqual CustomTerminationMessage
    }
  }
}
