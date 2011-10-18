/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import akka.japi.Creator
import akka.util._
import collection.immutable.Stack

/**
 * ActorRef configuration object, this is threadsafe and fully sharable
 *
 * Props() returns default configuration
 * FIXME document me
 */
object Props {
  import FaultHandlingStrategy._

  final val defaultCreator: () ⇒ Actor = () ⇒ throw new UnsupportedOperationException("No actor creator specified!")
  final val defaultDispatcher: MessageDispatcher = null
  final val defaultTimeout: Timeout = Timeout(Duration.MinusInf)
  final val defaultDecider: Decider = {
    case _: ActorInitializationException ⇒ Stop
    case _: Exception                    ⇒ Restart
    case _                               ⇒ Escalate
  }
  final val defaultFaultHandler: FaultHandlingStrategy = OneForOneStrategy(defaultDecider, None, None)
  final val defaultSupervisor: Option[ActorRef] = None
  final val noHotSwap: Stack[Actor.Receive] = Stack.empty
  final val randomAddress: String = ""

  /**
   * The default Props instance, uses the settings from the Props object starting with default*
   */
  final val default = new Props()

  /**
   * Returns a cached default implementation of Props
   */
  def apply(): Props = default

  def empty = Props(context ⇒ { case null ⇒ })

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * of the supplied type using the default constructor
   */
  def apply[T <: Actor: ClassManifest]: Props =
    default.withCreator(implicitly[ClassManifest[T]].erasure.asInstanceOf[Class[_ <: Actor]].newInstance)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * of the supplied class using the default constructor
   */
  def apply(actorClass: Class[_ <: Actor]): Props =
    default.withCreator(actorClass.newInstance)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * using the supplied thunk
   */
  def apply(creator: ⇒ Actor): Props = default.withCreator(creator)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * using the supplied thunk
   */
  def apply(creator: Creator[_ <: Actor]): Props = default.withCreator(creator.create)

  def apply(behavior: ActorContext ⇒ Actor.Receive): Props = apply(new Actor { def receive = behavior(context) })

  def apply(faultHandler: FaultHandlingStrategy): Props = apply(new Actor { def receive = { case _ ⇒ } }).withFaultHandler(faultHandler)
}

/**
 * ActorRef configuration object, this is thread safe and fully sharable
 */
case class Props(creator: () ⇒ Actor = Props.defaultCreator,
                 @transient dispatcher: MessageDispatcher = Props.defaultDispatcher,
                 timeout: Timeout = Props.defaultTimeout,
                 faultHandler: FaultHandlingStrategy = Props.defaultFaultHandler) {
  /**
   * No-args constructor that sets all the default values
   * Java API
   */
  def this() = this(
    creator = Props.defaultCreator,
    dispatcher = Props.defaultDispatcher,
    timeout = Props.defaultTimeout,
    faultHandler = Props.defaultFaultHandler)

  /**
   * Returns a new Props with the specified creator set
   *  Scala API
   */
  def withCreator(c: ⇒ Actor) = copy(creator = () ⇒ c)

  /**
   * Returns a new Props with the specified creator set
   *  Java API
   */
  def withCreator(c: Creator[Actor]) = copy(creator = () ⇒ c.create)

  /**
   * Returns a new Props with the specified creator set
   *  Java API
   */
  def withCreator(c: Class[_ <: Actor]) = copy(creator = () ⇒ c.newInstance)

  /**
   * Returns a new Props with the specified dispatcher set
   *  Java API
   */
  def withDispatcher(d: MessageDispatcher) = copy(dispatcher = d)

  /**
   * Returns a new Props with the specified timeout set
   * Java API
   */
  def withTimeout(t: Timeout) = copy(timeout = t)

  /**
   * Returns a new Props with the specified faulthandler set
   * Java API
   */
  def withFaultHandler(f: FaultHandlingStrategy) = copy(faultHandler = f)

}
