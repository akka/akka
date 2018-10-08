/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.PersistentBehaviors

/*
Comparing a Behavior with a PersistentBehavior
 */

object HelloWorld {
  final case class Greet(whom: String, replyTo: ActorRef[Greeting])
  final case class Greeting(whom: String, numberOfPeople: Int)

  private final case class KnownPeople(names: Set[String]) {
    def add(name: String): KnownPeople = copy(names = names + name)
    def numberOfPeople: Int = names.size
  }

  val behavior: Behavior[Greet] = greeter(KnownPeople(Set.empty))

  private def greeter(people: KnownPeople): Behavior[Greet] =
    Behaviors.receiveMessage { msg ⇒
      msg.replyTo ! Greeting(msg.whom, people.numberOfPeople)
      greeter(people.add(msg.whom))
    }
}

object PersistentHelloWorld {
  // Command
  final case class Greet(whom: String, replyTo: ActorRef[Greeting])
  // Response
  final case class Greeting(whom: String, numberOfPeople: Int)
  // Event
  final case class Greeted(whom: String)

  // State
  private final case class KnownPeople(names: Set[String]) {
    def add(name: String): KnownPeople = copy(names = names + name)
    def numberOfPeople: Int = names.size
  }

  private val commandHandler: (KnownPeople, Greet) ⇒ Effect[Greeted, KnownPeople] = {
    (_, cmd) ⇒
      val event = Greeted(cmd.whom)
      Effect.persist(event)
        .thenRun(state ⇒ cmd.replyTo ! Greeting(cmd.whom, state.numberOfPeople))
  }

  private val eventHandler: (KnownPeople, Greeted) ⇒ KnownPeople = {
    (state, evt) ⇒ state.add(evt.whom)
  }

  def behavior(entityId: String): Behavior[Greet] = PersistentBehaviors.receive(
    persistenceId = s"HelloWorld-$entityId",
    emptyState = KnownPeople(Set.empty),
    commandHandler,
    eventHandler
  )

}
