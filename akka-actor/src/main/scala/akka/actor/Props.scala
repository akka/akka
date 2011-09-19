/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.config.Supervision._
import akka.dispatch._
import akka.japi.Creator
import akka.util._

/**
 * ActorRef configuration object, this is threadsafe and fully sharable
 *
 * Props() returns default configuration
 * FIXME document me
 */
object Props {
  final val defaultCreator: () ⇒ Actor = () ⇒ throw new UnsupportedOperationException("No actor creator specified!")
  final val defaultDeployId: String = ""
  final val defaultDispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher
  final val defaultTimeout: Timeout = Timeout(Duration(Actor.TIMEOUT, "millis"))
  final val defaultFaultHandler: FaultHandlingStrategy = AllForOnePermanentStrategy(classOf[Exception] :: Nil, None, None)
  final val defaultSupervisor: Option[ActorRef] = None

  /**
   * The default Props instance, uses the settings from the Props object starting with default*
   */
  final val default = new Props()

  /**
   * Returns a cached default implementation of Props
   */
  def apply(): Props = default

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

  def apply(behavior: ActorRef ⇒ Actor.Receive): Props = apply(new Actor { def receive = behavior(self) })
}

/**
 * ActorRef configuration object, this is thread safe and fully sharable
 */
case class Props(creator: () ⇒ Actor = Props.defaultCreator,
                 @transient dispatcher: MessageDispatcher = Props.defaultDispatcher,
                 timeout: Timeout = Props.defaultTimeout,
                 faultHandler: FaultHandlingStrategy = Props.defaultFaultHandler,
                 supervisor: Option[ActorRef] = Props.defaultSupervisor) {
  /**
   * No-args constructor that sets all the default values
   * Java API
   */
  def this() = this(
    creator = Props.defaultCreator,
    dispatcher = Props.defaultDispatcher,
    timeout = Props.defaultTimeout,
    faultHandler = Props.defaultFaultHandler,
    supervisor = Props.defaultSupervisor)

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

  /**
   * Returns a new Props with the specified supervisor set, if null, it's equivalent to withSupervisor(Option.none())
   * Java API
   */
  def withSupervisor(s: ActorRef) = copy(supervisor = Option(s))

  /**
   * Returns a new Props with the specified supervisor set
   * Java API
   */
  def withSupervisor(s: akka.japi.Option[ActorRef]) = copy(supervisor = s.asScala)

  /**
   * Returns a new Props with the specified supervisor set
   * Scala API
   */
  def withSupervisor(s: scala.Option[ActorRef]) = copy(supervisor = s)
}
