/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import scala.concurrent.Future
import scala.concurrent.Promise

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.annotation.InternalApi

object WatchExtension extends ExtensionId[WatchExtension] {
  override def createExtension(system: ActorSystem[_]): WatchExtension =
    new WatchExtension(system)

  def get(system: ActorSystem[_]): WatchExtension = apply(system)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] object Watcher {
    sealed trait Command
    final case class Watch(ref: ActorRef[_], promise: Promise[Done]) extends Command
    private final case class Stopped(promise: Promise[Done]) extends Command

    def apply(): Behavior[Command] = {
      Behaviors.receive { (context, command) =>
        command match {
          case Watch(ref, promise) =>
            context.watchWith(ref, Stopped(promise))
            Behaviors.same
          case Stopped(promise) =>
            promise.trySuccess(Done)
            Behaviors.same
        }
      }
    }
  }
}

/**
 * Convenience for watching actors from code that is not an actor itself.
 */
class WatchExtension(system: ActorSystem[_]) extends Extension {
  import WatchExtension.Watcher

  private val watcher: ActorRef[Watcher.Command] =
    system.systemActorOf(Watcher(), "watchExtension")

  def watch[T](ref: ActorRef[_]): Future[Done] = {
    val terminated = Promise[Done]()
    watcher ! Watcher.Watch(ref, terminated)
    terminated.future
  }

}
