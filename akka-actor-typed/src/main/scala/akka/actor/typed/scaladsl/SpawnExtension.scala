/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import scala.concurrent.Future

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.actor.typed.Props
import akka.actor.typed.SpawnProtocol
import akka.util.Timeout

object SpawnExtension extends ExtensionId[SpawnExtension] {
  override def createExtension(system: ActorSystem[_]): SpawnExtension =
    new SpawnExtension(system)

  def get(system: ActorSystem[_]): SpawnExtension = apply(system)
}

/**
 * Convenience for spawning actors from code that is not an actor itself.
 */
class SpawnExtension(system: ActorSystem[_]) extends Extension {
  private val parent: ActorRef[SpawnProtocol.Command] =
    system.systemActorOf(SpawnProtocol(), "spawnExtension")
  private implicit val spawnTimeout: Timeout = system.settings.classicSettings.CreationTimeout

  /**
   * If `name` is an empty string an anonymous actor (with automatically generated name) will be created.
   *
   * If the `name` is already taken of an existing actor a unique name will be used by appending a suffix
   * to the the `name`. The exact format or value of the suffix is an implementation detail that is
   * undefined. This means that reusing the same name for several actors will not result in
   * `InvalidActorNameException`, but it's better to use unique names to begin with.
   */
  def spawn[T](behavior: Behavior[T], name: String, props: Props): Future[ActorRef[T]] = {
    implicit val sys: ActorSystem[_] = system
    import akka.actor.typed.scaladsl.AskPattern._
    parent.ask[ActorRef[T]](replyTo => SpawnProtocol.Spawn(behavior, name, props, replyTo))
  }

  def spawn[T](behavior: Behavior[T], name: String): Future[ActorRef[T]] =
    spawn(behavior, name, Props.empty)
}
