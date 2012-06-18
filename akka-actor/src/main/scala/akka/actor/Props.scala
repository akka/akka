/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import akka.japi.Creator
import akka.routing._

/**
 * Factory for Props instances.
 *
 * Props is a ActorRef configuration object, that is thread safe and fully sharable.
 *
 * Used when creating new actors through; <code>ActorSystem.actorOf</code> and <code>ActorContext.actorOf</code>.
 */
object Props {

  /**
   * The defaultCreator, simply throws an UnsupportedOperationException when applied, which is used when creating a Props
   */
  final val defaultCreator: () ⇒ Actor = () ⇒ throw new UnsupportedOperationException("No actor creator specified!")

  /**
   * The defaultRoutedProps is NoRouter which is used when creating a Props
   */
  final val defaultRoutedProps: RouterConfig = NoRouter

  /**
   * The default Deploy instance which is used when creating a Props
   */
  final val defaultDeploy = Deploy()

  /**
   * A Props instance whose creator will create an actor that doesn't respond to any message
   */
  final val empty = new Props(() ⇒ new Actor { def receive = Actor.emptyBehavior })

  /**
   * The default Props instance, uses the settings from the Props object starting with default*.
   */
  final val default = new Props()

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * of the supplied type using the default constructor.
   *
   * Scala API.
   */
  def apply[T <: Actor: ClassManifest](): Props =
    default.withCreator(implicitly[ClassManifest[T]].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * of the supplied class using the default constructor.
   */
  def apply(actorClass: Class[_ <: Actor]): Props = default.withCreator(actorClass)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * using the supplied thunk.
   *
   * Scala API.
   */
  def apply(creator: ⇒ Actor): Props = default.withCreator(creator)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * using the supplied thunk.
   */
  def apply(creator: Creator[_ <: Actor]): Props = default.withCreator(creator.create)

  /**
   * Returns a new Props whose creator will instantiate an Actor that has the behavior specified
   */
  def apply(behavior: ActorContext ⇒ Actor.Receive): Props = apply(new Actor { def receive = behavior(context) })
}

/**
 * Props is a ActorRef configuration object, that is thread safe and fully sharable.
 * Used when creating new actors through; <code>ActorSystem.actorOf</code> and <code>ActorContext.actorOf</code>.
 *
 * In case of providing code which creates the actual Actor instance, that must not return the same instance multiple times.
 *
 * Examples on Scala API:
 * {{{
 *  val props = Props[MyActor]
 *  val props = Props(new MyActor)
 *  val props = Props(
 *    creator = ..,
 *    dispatcher = ..,
 *    routerConfig = ..
 *  )
 *  val props = Props().withCreator(new MyActor)
 *  val props = Props[MyActor].withRouter(RoundRobinRouter(..))
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
 *  Props props = new Props(MyActor.class).withRouter(new RoundRobinRouter(..));
 * }}}
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed when SI-4804 is fixed
case class Props(
  creator: () ⇒ Actor = Props.defaultCreator,
  dispatcher: String = Dispatchers.DefaultDispatcherId,
  routerConfig: RouterConfig = Props.defaultRoutedProps,
  deploy: Deploy = Props.defaultDeploy) {

  /**
   * No-args constructor that sets all the default values.
   */
  def this() = this(
    creator = Props.defaultCreator,
    dispatcher = Dispatchers.DefaultDispatcherId)

  /**
   * Java API.
   */
  def this(factory: UntypedActorFactory) = this(
    creator = () ⇒ factory.create(),
    dispatcher = Dispatchers.DefaultDispatcherId)

  /**
   * Java API.
   */
  def this(actorClass: Class[_ <: Actor]) = this(
    creator = FromClassCreator(actorClass),
    dispatcher = Dispatchers.DefaultDispatcherId,
    routerConfig = Props.defaultRoutedProps)

  /**
   * Returns a new Props with the specified creator set.
   *
   * The creator must not return the same instance multiple times.
   *
   * Scala API.
   */
  def withCreator(c: ⇒ Actor): Props = copy(creator = () ⇒ c)

  /**
   * Returns a new Props with the specified creator set.
   *
   * The creator must not return the same instance multiple times.
   *
   * Java API.
   */
  def withCreator(c: Creator[Actor]): Props = copy(creator = () ⇒ c.create)

  /**
   * Returns a new Props with the specified creator set.
   *
   * Java API.
   */
  def withCreator(c: Class[_ <: Actor]): Props = copy(creator = FromClassCreator(c))

  /**
   * Returns a new Props with the specified dispatcher set.
   */
  def withDispatcher(d: String): Props = copy(dispatcher = d)

  /**
   * Returns a new Props with the specified router config set.
   */
  def withRouter(r: RouterConfig): Props = copy(routerConfig = r)

  /**
   * Returns a new Props with the specified deployment configuration.
   */
  def withDeploy(d: Deploy): Props = copy(deploy = d)

}

/**
 * Used when creating an Actor from a class. Special Function0 to be
 * able to optimize serialization.
 */
private[akka] case class FromClassCreator(clazz: Class[_ <: Actor]) extends Function0[Actor] {
  def apply(): Actor = try clazz.newInstance catch {
    case iae: IllegalAccessException ⇒
      val ctor = clazz.getDeclaredConstructor()
      ctor.setAccessible(true)
      ctor.newInstance()
  }
}
