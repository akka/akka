/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed.internal

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Props }
import akka.annotation.InternalApi

import scala.concurrent.{ Await, TimeoutException }
import scala.concurrent.duration.Duration

@InternalApi
private[akka] object TestKitGuardian {
  sealed trait TestKitCommand
  final case class SpawnActor[T](name: String, behavior: Behavior[T], replyTo: ActorRef[ActorRef[T]], props: Props) extends TestKitCommand
  final case class SpawnActorAnonymous[T](behavior: Behavior[T], replyTo: ActorRef[ActorRef[T]], props: Props) extends TestKitCommand

  val testKitGuardian = Behaviors.immutable[TestKitCommand] {
    case (ctx, SpawnActor(name, behavior, reply, props)) ⇒
      reply ! ctx.spawn(behavior, name, props)
      Behaviors.same
    case (ctx, SpawnActorAnonymous(behavior, reply, props)) ⇒
      reply ! ctx.spawnAnonymous(behavior, props)
      Behaviors.same
  }
}

@InternalApi
private[akka] object TestKitImpl {

  // common impls for Java and Scala

  def shutdown(
    system:               ActorSystem[_],
    duration:             Duration,
    verifySystemShutdown: Boolean): Unit = {
    system.terminate()
    try Await.ready(system.whenTerminated, duration) catch {
      case _: TimeoutException ⇒
        val msg = "Failed to stop [%s] within [%s] \n%s".format(system.name, duration,
          system.printTree)
        if (verifySystemShutdown) throw new RuntimeException(msg)
        else println(msg)
    }
  }
}
