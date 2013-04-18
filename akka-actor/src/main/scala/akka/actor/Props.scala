/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.dispatch._
import akka.japi.Creator
import scala.reflect.ClassTag
import akka.routing._
import akka.util.Reflect
import scala.annotation.varargs
import Deploy.{ NoDispatcherGiven, NoMailboxGiven }
import scala.collection.immutable

/**
 * Factory for Props instances.
 *
 * Props is a ActorRef configuration object, that is immutable, so it is thread safe and fully sharable.
 *
 * Used when creating new actors through <code>ActorSystem.actorOf</code> and <code>ActorContext.actorOf</code>.
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
   * The default Props instance, uses the settings from the Props object starting with default*.
   */
  final val default = new Props()

  /**
   * A Props instance whose creator will create an actor that doesn't respond to any message
   */
  final val empty = Props[EmptyActor]

  /**
   * INTERNAL API
   *
   * (Not because it is so immensely complicated, only because we might remove it if no longer needed internally)
   */
  private[akka] class EmptyActor extends Actor {
    def receive = Actor.emptyBehavior
  }

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * of the supplied type using the default constructor.
   *
   * Scala API.
   */
  def apply[T <: Actor: ClassTag](): Props = apply(implicitly[ClassTag[T]].runtimeClass)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * using the supplied thunk.
   *
   * Scala API.
   */
  @deprecated("give class and arguments instead", "2.2")
  def apply(creator: ⇒ Actor): Props = default.withCreator(creator)

  /**
   * Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * using the supplied thunk.
   */
  @deprecated("give class and arguments instead", "2.2")
  def apply(creator: Creator[_ <: Actor]): Props = default.withCreator(creator.create)

  /**
   * The deprecated legacy constructor.
   */
  @deprecated("give class and arguments instead", "2.2")
  def apply(
    creator: () ⇒ Actor = Props.defaultCreator,
    dispatcher: String = Dispatchers.DefaultDispatcherId,
    routerConfig: RouterConfig = Props.defaultRoutedProps,
    deploy: Deploy = Props.defaultDeploy): Props = {

    val d1 = if (dispatcher != Dispatchers.DefaultDispatcherId) deploy.copy(dispatcher = dispatcher) else deploy
    val d2 = if (routerConfig != Props.defaultRoutedProps) d1.copy(routerConfig = routerConfig) else d1
    val p = Props(classOf[CreatorFunctionConsumer], creator)
    if (d2 != Props.defaultDeploy) p.withDeploy(d2) else p
  }

  /**
   * Scala API: create a Props given a class and its constructor arguments.
   */
  def apply(clazz: Class[_], args: Any*): Props = apply(defaultDeploy, clazz, args.toVector)

  /**
   * Java API: create a Props given a class and its constructor arguments.
   */
  @varargs
  def create(clazz: Class[_], args: AnyRef*): Props = apply(defaultDeploy, clazz, args.toVector)
}

/**
 * Props is a configuration object using in creating an [[Actor]]; it is
 * immutable, so it is thread-safe and fully shareable.
 *
 * Examples on Scala API:
 * {{{
 *  val props = Props.empty
 *  val props = Props[MyActor]
 *  val props = Props(classOf[MyActor], arg1, arg2)
 *
 *  val otherProps = props.withDispatcher("dispatcher-id")
 *  val otherProps = props.withDeploy(<deployment info>)
 * }}}
 *
 * Examples on Java API:
 * {{{
 *  final Props props = Props.empty();
 *  final Props props = Props.create(MyActor.class, arg1, arg2);
 *
 *  final Props otherProps = props.withDispatcher("dispatcher-id");
 *  final Props otherProps = props.withDeploy(<deployment info>);
 * }}}
 */
@SerialVersionUID(2L)
case class Props(deploy: Deploy, clazz: Class[_], args: immutable.Seq[Any]) {

  // validate constructor signature; throws IllegalArgumentException if invalid
  Reflect.findConstructor(clazz, args)

  /**
   * No-args constructor that sets all the default values.
   *
   * @deprecated use `Props.create(clazz, args ...)` instead
   */
  @deprecated("use Props.create()", "2.2")
  def this() = this(Props.defaultDeploy, classOf[CreatorFunctionConsumer], Vector(Props.defaultCreator))

  /**
   * Java API: create Props from an [[UntypedActorFactory]]
   *
   * @deprecated use `Props.create(clazz, args ...)` instead; this method has been
   *             deprecated because it encourages creating Props which contain
   *             non-serializable inner classes, making them also
   *             non-serializable
   */
  @deprecated("use constructor which takes the actor class directly", "2.2")
  def this(factory: UntypedActorFactory) = this(Props.defaultDeploy, classOf[UntypedActorFactoryConsumer], Vector(factory))

  /**
   * Java API: create Props from a given [[Class]]
   *
   * @deprecated use Props.create(clazz) instead; deprecated since it duplicates
   *             another API
   */
  @deprecated("use Props.create()", "2.2")
  def this(actorClass: Class[_ <: Actor]) = this(Props.defaultDeploy, actorClass, Vector.empty)

  @deprecated("use newActor()", "2.2")
  def creator: () ⇒ Actor = newActor

  /**
   * Convenience method for extracting the dispatcher information from the
   * contained [[Deploy]] instance.
   */
  def dispatcher: String = deploy.dispatcher match {
    case NoDispatcherGiven ⇒ Dispatchers.DefaultDispatcherId
    case x                 ⇒ x
  }

  /**
   * Convenience method for extracting the mailbox information from the
   * contained [[Deploy]] instance.
   */
  def mailbox: Option[String] = deploy.mailbox match {
    case NoMailboxGiven ⇒ None
    case x              ⇒ Some(x)
  }

  /**
   * Convenience method for extracting the router configuration from the
   * contained [[Deploy]] instance.
   */
  def routerConfig: RouterConfig = deploy.routerConfig

  /**
   * Scala API: Returns a new Props with the specified creator set.
   *
   * The creator must not return the same instance multiple times.
   */
  @deprecated("move actor into named class and use withCreator(clazz)", "2.2")
  def withCreator(c: ⇒ Actor): Props = copy(clazz = classOf[CreatorFunctionConsumer], args = Vector(() ⇒ c))

  /**
   * Java API: Returns a new Props with the specified creator set.
   *
   * The creator must not return the same instance multiple times.
   *
   * @deprecated use `Props.create(clazz, args ...)` instead; this method has been
   *             deprecated because it encourages creating Props which contain
   *             non-serializable inner classes, making them also
   *             non-serializable
   */
  @deprecated("use Props.create(clazz, args ...) instead", "2.2")
  def withCreator(c: Creator[Actor]): Props = copy(clazz = classOf[CreatorConsumer], args = Vector(c))

  /**
   * Returns a new Props with the specified creator set.
   *
   * @deprecated use Props.create(clazz) instead; deprecated since it duplicates
   *             another API
   */
  @deprecated("use Props(clazz, args).withDeploy(other.deploy)", "2.2")
  def withCreator(c: Class[_ <: Actor]): Props = copy(clazz = c, args = Vector.empty)

  /**
   * Returns a new Props with the specified dispatcher set.
   */
  def withDispatcher(d: String): Props = copy(deploy = deploy.copy(dispatcher = d))

  /**
   * Returns a new Props with the specified mailbox set.
   */
  def withMailbox(m: String): Props = copy(deploy = deploy.copy(mailbox = m))

  /**
   * Returns a new Props with the specified router config set.
   */
  def withRouter(r: RouterConfig): Props = copy(deploy = deploy.copy(routerConfig = r))

  /**
   * Returns a new Props with the specified deployment configuration.
   */
  def withDeploy(d: Deploy): Props = copy(deploy = d withFallback deploy)

  /**
   * Create a new actor instance. This method is only useful when called during
   * actor creation by the ActorSystem, i.e. for user-level code it can only be
   * used within the implementation of [[IndirectActorProducer#produce]].
   */
  def newActor(): Actor = {
    Reflect.instantiate(clazz, args) match {
      case a: Actor                 ⇒ a
      case i: IndirectActorProducer ⇒ i.produce()
      case _                        ⇒ throw new IllegalArgumentException(s"unknown actor creator [$clazz]")
    }
  }

  /**
   * Obtain an upper-bound approximation of the actor class which is going to
   * be created by these Props. In other words, the [[#newActor]] method will
   * produce an instance of this class or a subclass thereof. This is used by
   * the actor system to select special dispatchers or mailboxes in case
   * dependencies are encoded in the actor type.
   */
  def actorClass(): Class[_ <: Actor] = {
    if (classOf[IndirectActorProducer].isAssignableFrom(clazz)) {
      Reflect.instantiate(clazz, args).asInstanceOf[IndirectActorProducer].actorClass
    } else if (classOf[Actor].isAssignableFrom(clazz)) {
      clazz.asInstanceOf[Class[_ <: Actor]]
    } else {
      throw new IllegalArgumentException("unknown actor creator [$clazz]")
    }
  }
}

/**
 * This interface defines a class of actor creation strategies deviating from
 * the usual default of just reflectively instantiating the [[Actor]]
 * subclass. It can be used to allow a dependency injection framework to
 * determine the actual actor class and how it shall be instantiated.
 */
trait IndirectActorProducer {

  /**
   * This factory method must produce a fresh actor instance upon each
   * invocation. <b>It is not permitted to return the same instance more than
   * once.</b>
   */
  def produce(): Actor

  /**
   * This method is used by [[Props]] to determine the type of actor which will
   * be created. This means that an instance of this `IndirectActorProducer`
   * will be created in order to call this method during any call to
   * [[Props#actorClass]]; it should be noted that such calls may
   * performed during actor set-up before the actual actor’s instantiation, and
   * that the instance created for calling `actorClass` is not necessarily reused
   * later to produce the actor.
   */
  def actorClass: Class[_ <: Actor]
}

/**
 * INTERNAL API
 */
private[akka] class UntypedActorFactoryConsumer(factory: UntypedActorFactory) extends IndirectActorProducer {
  override def actorClass = classOf[Actor]
  override def produce() = factory.create()
}

/**
 * INTERNAL API
 */
private[akka] class CreatorFunctionConsumer(creator: () ⇒ Actor) extends IndirectActorProducer {
  override def actorClass = classOf[Actor]
  override def produce() = creator()
}

/**
 * INTERNAL API
 */
private[akka] class CreatorConsumer(creator: Creator[Actor]) extends IndirectActorProducer {
  override def actorClass = classOf[Actor]
  override def produce() = creator.create()
}

/**
 * INTERNAL API
 */
private[akka] class TypedCreatorFunctionConsumer(clz: Class[_ <: Actor], creator: () ⇒ Actor) extends IndirectActorProducer {
  override def actorClass = clz
  override def produce() = creator()
}
