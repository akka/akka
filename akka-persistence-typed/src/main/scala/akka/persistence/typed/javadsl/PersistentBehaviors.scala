package akka.persistence.typed.javadsl

import akka.actor.typed.Behavior.UntypedBehavior
import akka.actor.typed.javadsl.ActorContext
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.japi.{ function ⇒ japi }
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.PersistentBehaviors._

import scala.collection.JavaConverters._
import akka.actor.typed.scaladsl.{ ActorContext ⇒ SAC }

class PersistentBehavior[Command, Event, State](
  @InternalApi private[akka] val persistenceId: String ⇒ String,
  initialState:                                 State,
  commandHandler:                               CommandHandler[Command, Event, State],
  eventHandler:                                 EventHandler[State, Event],
  recoveryCompleted:                            (SAC[Command], State) ⇒ Unit,
  tagger:                                       Event ⇒ Set[String],
  snapshotOn:                                   (State, Event, Long) ⇒ Boolean) extends UntypedBehavior[Command] {

  private val pa = new scaladsl.PersistentBehavior[Command, Event, State](
    name ⇒ persistenceId(name),
    initialState,
    (ctx, s, e) ⇒ commandHandler.apply(ctx.asJava, s, e).asInstanceOf[EffectImpl[Event, State]],
    (s, e) ⇒ eventHandler.apply(s, e),
    recoveryCompleted,
    tagger,
    snapshotOn
  )

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: japi.Function2[ActorContext[Command], State, Unit]): PersistentBehavior[Command, Event, State] =
    copy(recoveryCompleted = (ctx, s) ⇒ callback.apply(ctx.asJava, s))

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def snapshotOn(predicate: japi.Function3[State, Event, Long, Boolean]): PersistentBehavior[Command, Event, State] =
    copy(snapshotOn = (s, e, seqNr) ⇒ predicate.apply(s, e, seqNr))

  /**
   * Snapshot every N events
   *
   * `numberOfEvents` should be greater than 0
   */
  def snapshotEvery(numberOfEvents: Long): PersistentBehavior[Command, Event, State] = {
    require(numberOfEvents > 0, s"numberOfEvents should be positive: Was $numberOfEvents")
    copy(snapshotOn = (_, _, seqNr) ⇒ seqNr % numberOfEvents == 0)
  }

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: japi.Function[Event, java.util.Set[String]]): PersistentBehavior[Command, Event, State] =
    copy(tagger = (e) ⇒ tagger.apply(e).asScala.toSet)

  private def copy(
    persistenceIdFromActorName: String ⇒ String                       = persistenceId,
    initialState:               State                                 = initialState,
    commandHandler:             CommandHandler[Command, Event, State] = commandHandler,
    eventHandler:               EventHandler[State, Event]            = eventHandler,
    recoveryCompleted:          (SAC[Command], State) ⇒ Unit          = recoveryCompleted,
    tagger:                     Event ⇒ Set[String]                   = tagger,
    snapshotOn:                 (State, Event, Long) ⇒ Boolean        = snapshotOn): PersistentBehavior[Command, Event, State] =
    new PersistentBehavior(persistenceIdFromActorName, initialState, commandHandler, eventHandler, recoveryCompleted, tagger, snapshotOn)

  /**
   * INTERNAL API
   */
  override private[akka] def untypedProps = pa.untypedProps
}

object Effect {
  /**
   * Persist a single event
   */
  final def persist[Event, State](event: Event): Effect[Event, State] = Persist(event)

  /**
   * Persist all of a the given events. Each event will be applied through `applyEffect` separately but not until
   * all events has been persisted. If an `afterCallBack` is added through [[Effect#andThen]] that will invoked
   * after all the events has been persisted.
   */
  final def persist[Event, State](events: java.util.List[Event]): Effect[Event, State] = PersistAll(events.asScala.toVector)

}

@DoNotInherit abstract class Effect[+Event, State] {
  self: EffectImpl[Event, State] ⇒
  /** Convenience method to register a side effect with just a callback function */
  final def andThen(callback: japi.Procedure[State]): Effect[Event, State] =
    CompositeEffect(this, SideEffect[Event, State](s ⇒ callback.apply(s)))
}

// Went for this over a Function3 otherwise the type is terribly long
//FIXME docs
trait CommandHandler[Command, Event, State] {
  def apply(ctx: ActorContext[Command], state: State, command: Command): Effect[Event, State]
}

//FIXME docs
trait EventHandler[State, Event] {
  def apply(state: State, event: Event): State
}

object PersistentBehaviors {
  /**
   * Create a `Behavior` for a persistent actor.
   */
  def immutable[Command, Event, State](
    persistenceId:  String,
    initialState:   State,
    commandHandler: CommandHandler[Command, Event, State],
    eventHandler:   EventHandler[State, Event]): PersistentBehavior[Command, Event, State] =
    persistentEntity(_ ⇒ persistenceId, initialState, commandHandler, eventHandler)

  /**
   * Create a `Behavior` for a persistent actor in Cluster Sharding, when the persistenceId is not known
   * until the actor is started and typically based on the entityId, which
   * is the actor name.
   *
   * TODO This will not be needed when it can be wrapped in `Behaviors.deferred`.
   */
  def persistentEntity[Command, Event, State](
    persistenceIdFromActorName: String ⇒ String,
    initialState:               State,
    commandHandler:             CommandHandler[Command, Event, State],
    eventHandler:               EventHandler[State, Event]): PersistentBehavior[Command, Event, State] =
    new PersistentBehavior[Command, Event, State](
      persistenceIdFromActorName,
      initialState,
      commandHandler,
      eventHandler,
      (_, _) ⇒ (),
      _ ⇒ Set.empty,
      (_, _, _) ⇒ false)

}
