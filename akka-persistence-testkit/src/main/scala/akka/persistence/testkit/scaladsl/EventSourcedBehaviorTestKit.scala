/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import scala.collection.immutable
import scala.reflect.ClassTag

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.annotation.DoNotInherit
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.internal.EventSourcedBehaviorTestKitImpl

object EventSourcedBehaviorTestKit {

  val config: Config = ConfigFactory.parseString("""
  akka.persistence.testkit.events.serialize = off
  """).withFallback(PersistenceTestKitPlugin.config)

  object SerializationSettings {
    val enabled: SerializationSettings = SerializationSettings(
      enabled = true,
      verifyEquality = false,
      verifyCommands = true,
      verifyEvents = true,
      verifyState = true)

    val disabled: SerializationSettings = SerializationSettings(
      enabled = false,
      verifyEquality = false,
      verifyCommands = false,
      verifyEvents = false,
      verifyState = false)
  }

  final case class SerializationSettings(
      enabled: Boolean,
      verifyEquality: Boolean,
      verifyCommands: Boolean,
      verifyEvents: Boolean,
      verifyState: Boolean)

  def apply[Command, Event, State](
      system: ActorSystem[_],
      behavior: Behavior[Command]): EventSourcedBehaviorTestKit[Command, Event, State] =
    new EventSourcedBehaviorTestKitImpl(ActorTestKit(system), behavior)

  @DoNotInherit trait CommandResult[Command, Event, State] {
    def command: Command
    def events: immutable.Seq[Event]

    def isNoEvents: Boolean

    /**
     * The first event. It will throw `AssertionError` if there is no event.
     */
    def event: Event

    /**
     * The first event of a given expected type. It will throw `AssertionError` if there is no event or
     * if the event is of a different type.
     */
    def eventOfType[E <: Event: ClassTag]: E

    def state: State

    /**
     * The state of a given expected type. It will throw `AssertionError` if the state is of a different type.
     */
    def stateOfType[S <: State: ClassTag]: S
  }

  @DoNotInherit trait CommandResultWithReply[Command, Event, State, Reply]
      extends CommandResult[Command, Event, State] {

    /**
     * The reply. It will throw `AssertionError` if there was no reply.
     */
    def reply: Reply

    /**
     * The reply of a given expected type.  It will throw `AssertionError` if there is no reply or
     * if the reply is of a different type.
     */
    def replyOfType[R <: Reply: ClassTag]: R
  }

  @DoNotInherit trait RestartResult[State] {
    def state: State
  }

}

@DoNotInherit trait EventSourcedBehaviorTestKit[Command, Event, State] {

  import EventSourcedBehaviorTestKit._

  def setSerializationSettings(settings: SerializationSettings): EventSourcedBehaviorTestKit[Command, Event, State]

  def runCommand(command: Command): CommandResult[Command, Event, State]

  def runCommand[R](creator: ActorRef[R] => Command): CommandResultWithReply[Command, Event, State, R]

  def restart(): RestartResult[State]

  def clear(): Unit

  def persistenceTestKit: PersistenceTestKit
}
