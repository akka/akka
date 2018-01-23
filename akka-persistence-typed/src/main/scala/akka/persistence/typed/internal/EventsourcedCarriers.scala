package akka.persistence.typed.internal

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler

/** INTERNAL API: Used to carry user callbacks between behaviors of an Persistent Behavior */
private[akka] final case class EventsourcedCallbacks[Command, Event, State](
  initialState:      State,
  commandHandler:    CommandHandler[Command, Event, State],
  eventHandler:      (State, Event) ⇒ State,
  snapshotWhen:      (State, Event, Long) ⇒ Boolean,
  recoveryCompleted: (ActorContext[Command], State) ⇒ Unit,
  tagger:            Event ⇒ Set[String]
)

/** INTERNAL API: Used to carry settings between behaviors of an Persistent Behavior */
private[akka] final case class EventsourcedPluginIds(
  journalPluginId:  String,
  snapshotPluginId: String
)
