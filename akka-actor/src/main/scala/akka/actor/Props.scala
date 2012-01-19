/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import akka.japi.Creator
import akka.util._
import collection.immutable.Stack
import akka.routing._

/**
 * Factory for Props instances.
 *
 * Props is a ActorRef configuration object, that is thread safe and fully sharable.
 *
 * Used when creating new actors through; <code>ActorSystem.actorOf</code> and <code>ActorContext.actorOf</code>.
 */
object Props {
  import FaultHandlingStrategy._

  final val defaultCreator: () ⇒ Actor = () ⇒ throw new UnsupportedOperationException("No actor creator specified!")
  final val defaultDecider: Decider = {
    case _: ActorInitializationException ⇒ Stop
    case _: ActorKilledException         ⇒ Stop
    case _: Exception                    ⇒ Restart
    case _                               ⇒ Escalate
  }

  final val defaultRoutedProps: RouterConfig = NoRouter

  final val defaultFaultHandler: FaultHandlingStrategy = OneForOneStrategy(defaultDecider, None, None)
  final val noHotSwap: Stack[Actor.Receive] = Stack.empty
  final val empty = new Props(() ⇒ new Actor { def receive = Actor.emptyBehavior })

  /**
   * The default Props instance, uses the settings from the Props object starting with default*.
   */
  final val default = new Props()

  /**
   * Returns a cached default implementation of Props.
   */
  def apply(): Props = default

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * of the supplied type using the default constructor.
   *
   * Scala API.
   */
  def apply[T <: Actor: ClassManifest]: Props =
    default.withCreator(implicitly[ClassManifest[T]].erasure.asInstanceOf[Class[_ <: Actor]].newInstance)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * of the supplied class using the default constructor.
   */
  def apply(actorClass: Class[_ <: Actor]): Props =
    default.withCreator(actorClass.newInstance)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * using the supplied thunk.
   *
   * Scala API.
   */
  def apply(creator: ⇒ Actor): Props =
    default.withCreator(creator)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * using the supplied thunk.
   */
  def apply(creator: Creator[_ <: Actor]): Props =
    default.withCreator(creator.create)

  def apply(behavior: ActorContext ⇒ Actor.Receive): Props =
    apply(new Actor { def receive = behavior(context) })

  def apply(faultHandler: FaultHandlingStrategy): Props =
    apply(new Actor { def receive = { case _ ⇒ } }).withFaultHandler(faultHandler)
}

/**
 * Props is a ActorRef configuration object, that is thread safe and fully sharable.
 * Used when creating new actors through; <code>ActorSystem.actorOf</code> and <code>ActorContext.actorOf</code>.
 *
 * Examples on Scala API:
 * {{{
 *  val props = Props[MyActor]
 *  val props = Props(new MyActor)
 *  val props = Props(
 *    creator = ..,
 *    dispatcher = ..,
 *    faultHandler = ..,
 *    routerConfig = ..
 *  )
 *  val props = Props().withCreator(new MyActor)
 *  val props = Props[MyActor].withRouter(RoundRobinRouter(..))
 *  val props = Props[MyActor].withFaultHandler(OneForOneStrategy {
 *    case e: IllegalStateException ⇒ Resume
 *  })
 * }}}
 *
 * Examples on Java API:
 * {{{
 *  Props props = new Props();
 *  Props props = new Props(MyActor.class);
 *  Props props = new Props(new UntypedActorFactory() {
 *    public UntypedActor create() {
 *      return new MyActor();
 *    }
 *  });
 *  Props props = new Props().withCreator(new UntypedActorFactory() { ... });
 *  Props props = new Props(MyActor.class).withFaultHandler(new OneForOneStrategy(...));
 *  Props props = new Props(MyActor.class).withRouter(new RoundRobinRouter(..));
 * }}}
 */
case class Props(
  creator: () ⇒ Actor = Props.defaultCreator,
  dispatcher: String = Dispatchers.DefaultDispatcherId,
  faultHandler: FaultHandlingStrategy = Props.defaultFaultHandler,
  routerConfig: RouterConfig = Props.defaultRoutedProps) {

  /**
   * No-args constructor that sets all the default values.
   */
  def this() = this(
    creator = Props.defaultCreator,
    dispatcher = Dispatchers.DefaultDispatcherId,
    faultHandler = Props.defaultFaultHandler)

  /**
   * Java API.
   */
  def this(factory: UntypedActorFactory) = this(
    creator = () ⇒ factory.create(),
    dispatcher = Dispatchers.DefaultDispatcherId,
    faultHandler = Props.defaultFaultHandler)

  /**
   * Java API.
   */
  def this(actorClass: Class[_ <: Actor]) = this(
    creator = () ⇒ actorClass.newInstance,
    dispatcher = Dispatchers.DefaultDispatcherId,
    faultHandler = Props.defaultFaultHandler,
    routerConfig = Props.defaultRoutedProps)

  /**
   * Returns a new Props with the specified creator set.
   *
   * Scala API.
   */
  def withCreator(c: ⇒ Actor) = copy(creator = () ⇒ c)

  /**
   * Returns a new Props with the specified creator set.
   *
   * Java API.
   */
  def withCreator(c: Creator[Actor]) = copy(creator = () ⇒ c.create)

  /**
   * Returns a new Props with the specified creator set.
   *
   * Java API.
   */
  def withCreator(c: Class[_ <: Actor]) = copy(creator = () ⇒ c.newInstance)

  /**
   * Returns a new Props with the specified dispatcher set.
   */
  def withDispatcher(d: String) = copy(dispatcher = d)

  /**
   * Returns a new Props with the specified faulthandler set.
   */
  def withFaultHandler(f: FaultHandlingStrategy) = copy(faultHandler = f)

  /**
   * Returns a new Props with the specified router config set.
   */
  def withRouter(r: RouterConfig) = copy(routerConfig = r)
}
