/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem

object HelloWorldPersistentEntityExample {

  //#persistent-entity-usage
  import akka.cluster.sharding.typed.scaladsl.ClusterSharding
  import akka.cluster.sharding.typed.scaladsl.Entity
  import akka.util.Timeout

  class HelloWorldService(system: ActorSystem[_]) {
    import system.executionContext

    // registration at startup
    private val sharding = ClusterSharding(system)

    sharding.init(
      Entity(
        typeKey = HelloWorld.entityTypeKey,
        createBehavior = entityContext => HelloWorld.persistentEntity(entityContext.entityId)))

    private implicit val askTimeout: Timeout = Timeout(5.seconds)

    def greet(worldId: String, whom: String): Future[Int] = {
      val entityRef = sharding.entityRefFor(HelloWorld.entityTypeKey, worldId)
      val greeting = entityRef ? HelloWorld.Greet(whom)
      greeting.map(_.numberOfPeople)
    }

  }
  //#persistent-entity-usage

  //#persistent-entity
  import akka.actor.typed.Behavior
  import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
  import akka.cluster.sharding.typed.scaladsl.EventSourcedEntity
  import akka.persistence.typed.scaladsl.Effect

  object HelloWorld {

    // Command
    trait Command
    final case class Greet(whom: String)(val replyTo: ActorRef[Greeting]) extends Command
    // Response
    final case class Greeting(whom: String, numberOfPeople: Int)

    // Event
    final case class Greeted(whom: String)

    // State
    private final case class KnownPeople(names: Set[String]) {
      def add(name: String): KnownPeople = copy(names = names + name)

      def numberOfPeople: Int = names.size
    }

    private val commandHandler: (KnownPeople, Command) => Effect[Greeted, KnownPeople] = { (_, cmd) =>
      cmd match {
        case cmd: Greet => greet(cmd)
      }
    }

    private def greet(cmd: Greet): Effect[Greeted, KnownPeople] =
      Effect.persist(Greeted(cmd.whom)).thenRun(state => cmd.replyTo ! Greeting(cmd.whom, state.numberOfPeople))

    private val eventHandler: (KnownPeople, Greeted) => KnownPeople = { (state, evt) =>
      state.add(evt.whom)
    }

    val entityTypeKey: EntityTypeKey[Command] =
      EntityTypeKey[Command]("HelloWorld")

    def persistentEntity(entityId: String): Behavior[Command] =
      EventSourcedEntity(
        entityTypeKey = entityTypeKey,
        entityId = entityId,
        emptyState = KnownPeople(Set.empty),
        commandHandler,
        eventHandler)

  }
  //#persistent-entity

}
