/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent._
import scala.concurrent.duration._
import akka.typed.scaladsl.Actor._
import akka.typed.scaladsl.AskPattern._
import akka.testkit._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MonitoringSpec extends TypedSpec {

  trait Tests {
    implicit def system: ActorSystem[TypedSpec.Command]

    def `get notified of actor termination`(): Unit = {
      case object Stop
      case class StartWatching(watchee: ActorRef[_])

      val terminator = Await.result(system ? TypedSpec.Create(Immutable[Stop.type] {
        case (ctx, `Stop`) ⇒ Stopped
      }, "t"), 3.seconds /*.dilated*/ )

      val receivedTerminationSignal: Promise[Unit] = Promise()

      val watcher = Await.result(system ? TypedSpec.Create(Immutable[StartWatching] {
        case (ctx, StartWatching(watchee)) ⇒ ctx.watch(watchee); Same
      }.onSignal {
        case (ctx, Terminated(_)) ⇒ receivedTerminationSignal.success(()); Stopped
      }, "w"), 3.seconds /*.dilated*/ )

      watcher ! StartWatching(terminator)
      terminator ! Stop

      Await.result(receivedTerminationSignal.future, 3.seconds /*.dilated*/ )
    }

    def `get notified of actor termination with a custom message`(): Unit = {
      case object Stop

      sealed trait Message
      case object CustomTerminationMessage extends Message
      case class StartWatchingWith(watchee: ActorRef[_], msg: CustomTerminationMessage.type) extends Message

      val terminator = Await.result(system ? TypedSpec.Create(Immutable[Stop.type] {
        case (ctx, `Stop`) ⇒ Stopped
      }, "t"), 3.seconds /*.dilated*/ )

      val receivedTerminationSignal: Promise[Unit] = Promise()

      val watcher = Await.result(system ? TypedSpec.Create(Immutable[Message] {
        case (ctx, StartWatchingWith(watchee, msg)) ⇒
          ctx.watchWith(watchee, msg); Same
        case (ctx, `CustomTerminationMessage`) ⇒ receivedTerminationSignal.success(()); Stopped
      }, "w"), 3.seconds /*.dilated*/ )

      watcher ! StartWatchingWith(terminator, CustomTerminationMessage)
      terminator ! Stop

      Await.result(receivedTerminationSignal.future, 3.seconds /*.dilated*/ )
    }
  }

  object `Actor monitoring (native)` extends Tests with NativeSystem
  object `Actor monitoring (adapted)` extends Tests with AdaptedSystem
}
