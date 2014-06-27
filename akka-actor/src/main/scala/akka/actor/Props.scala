/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
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
import scala.language.existentials
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import scala.annotation.tailrec
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable

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
   * A Props instance whose creator will create an actor that doesn't respond to any message
   */
  final val empty = Props[EmptyActor]

  /**
   * The default Props instance, uses the settings from the Props object starting with default*.
   */
  final val default = Props(defaultDeploy, classOf[CreatorFunctionConsumer], List(defaultCreator))

  /**
   * INTERNAL API
   *
   * (Not because it is so immensely complicated, only because we might remove it if no longer needed internally)
   */
  private[akka] class EmptyActor extends Actor {
    def receive = Actor.emptyBehavior
  }

  /**
   * Scala API: Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * of the supplied type using the default constructor.
   */
  def apply[T <: Actor: ClassTag](): Props = apply(defaultDeploy, implicitly[ClassTag[T]].runtimeClass, List.empty)

  /**
   * Scala API: Returns a Props that has default values except for "creator" which will be a function that creates an instance
   * using the supplied thunk.
   *
   * CAVEAT: Required mailbox type cannot be detected when using anonymous mixin composition
   * when creating the instance. For example, the following will not detect the need for
   * `DequeBasedMessageQueueSemantics` as defined in `Stash`:
   * {{{
   * 'Props(new Actor with Stash { ... })
   * }}}
   * Instead you must create a named class that mixin the trait,
   * e.g. `class MyActor extends Actor with Stash`.
   */
  def apply[T <: Actor: ClassTag](creator: ⇒ T): Props =
    mkProps(implicitly[ClassTag[T]].runtimeClass, () ⇒ creator)

  private def mkProps(classOfActor: Class[_], ctor: () ⇒ Actor): Props =
    Props(classOf[TypedCreatorFunctionConsumer], classOfActor, ctor)

  /**
   * Scala API: create a Props given a class and its constructor arguments.
   */
  def apply(clazz: Class[_], args: Any*): Props = apply(defaultDeploy, clazz, args.toList)

  /**
   * Java API: create a Props given a class and its constructor arguments.
   */
  @varargs
  def create(clazz: Class[_], args: AnyRef*): Props = apply(defaultDeploy, clazz, args.toList)

  /**
   * Create new Props from the given [[akka.japi.Creator]].
   *
   * You can not use a Java 8 lambda with this method since the generated classes
   * don't carry enough type information.
   *
   * Use the Props.create(actorClass, creator) instead.
   */
  def create[T <: Actor](creator: Creator[T]): Props = {
    val cc = creator.getClass
    if ((cc.getEnclosingClass ne null) && (cc.getModifiers & Modifier.STATIC) == 0)
      throw new IllegalArgumentException("cannot use non-static local Creator to create actors; make it static (e.g. local to a static method) or top-level")
    val ac = classOf[Actor]
    val coc = classOf[Creator[_]]
    val actorClass = Reflect.findMarker(cc, coc) match {
      case t: ParameterizedType ⇒
        t.getActualTypeArguments.head match {
          case c: Class[_] ⇒ c // since T <: Actor
          case v: TypeVariable[_] ⇒
            v.getBounds collectFirst { case c: Class[_] if ac.isAssignableFrom(c) && c != ac ⇒ c } getOrElse ac
          case x ⇒ throw new IllegalArgumentException(s"unsupported type found in Creator argument [$x]")
        }
      case c: Class[_] if (c == coc) ⇒
        throw new IllegalArgumentException(s"erased Creator types are unsupported, use Props.create(actorClass, creator) instead")
    }
    apply(defaultDeploy, classOf[CreatorConsumer], actorClass :: creator :: Nil)
  }

  /**
   * Create new Props from the given [[akka.japi.Creator]] with the type set to the given actorClass.
   */
  def create[T <: Actor](actorClass: Class[T], creator: Creator[T]): Props = {
    apply(defaultDeploy, classOf[CreatorConsumer], actorClass :: creator :: Nil)
  }
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
final case class Props(deploy: Deploy, clazz: Class[_], args: immutable.Seq[Any]) {

  if (Modifier.isAbstract(clazz.getModifiers))
    throw new IllegalArgumentException(s"Actor class [${clazz.getName}] must not be abstract")

  // derived property, does not need to be serialized
  @transient
  private[this] var _producer: IndirectActorProducer = _

  // derived property, does not need to be serialized
  @transient
  private[this] var _cachedActorClass: Class[_ <: Actor] = _

  private[this] def producer: IndirectActorProducer = {
    if (_producer eq null)
      _producer = IndirectActorProducer(clazz, args)

    _producer
  }

  private[this] def cachedActorClass: Class[_ <: Actor] = {
    if (_cachedActorClass eq null)
      _cachedActorClass = producer.actorClass

    _cachedActorClass
  }

  // validate producer constructor signature; throws IllegalArgumentException if invalid
  producer

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
  def mailbox: String = deploy.mailbox match {
    case NoMailboxGiven ⇒ Mailboxes.DefaultMailboxId
    case x              ⇒ x
  }

  /**
   * Convenience method for extracting the router configuration from the
   * contained [[Deploy]] instance.
   */
  def routerConfig: RouterConfig = deploy.routerConfig

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
   * Obtain an upper-bound approximation of the actor class which is going to
   * be created by these Props. In other words, the actor factory method will
   * produce an instance of this class or a subclass thereof. This is used by
   * the actor system to select special dispatchers or mailboxes in case
   * dependencies are encoded in the actor type.
   */
  def actorClass(): Class[_ <: Actor] = cachedActorClass

  /**
   * INTERNAL API
   *
   * Create a new actor instance. This method is only useful when called during
   * actor creation by the ActorSystem, i.e. for user-level code it can only be
   * used within the implementation of [[IndirectActorProducer#produce]].
   */
  private[akka] def newActor(): Actor = {
    producer.produce()
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

private[akka] object IndirectActorProducer {
  val CreatorFunctionConsumerClass = classOf[CreatorFunctionConsumer]
  val CreatorConsumerClass = classOf[CreatorConsumer]
  val TypedCreatorFunctionConsumerClass = classOf[TypedCreatorFunctionConsumer]

  def apply(clazz: Class[_], args: immutable.Seq[Any]): IndirectActorProducer = {
    if (classOf[IndirectActorProducer].isAssignableFrom(clazz)) {
      def get1stArg[T]: T = args.head.asInstanceOf[T]
      def get2ndArg[T]: T = args.tail.head.asInstanceOf[T]
      // The cost of doing reflection to create these for every props
      // is rather high, so we match on them and do new instead
      clazz match {
        case TypedCreatorFunctionConsumerClass ⇒
          new TypedCreatorFunctionConsumer(get1stArg, get2ndArg)
        case CreatorFunctionConsumerClass ⇒
          new CreatorFunctionConsumer(get1stArg)
        case CreatorConsumerClass ⇒
          new CreatorConsumer(get1stArg, get2ndArg)
        case _ ⇒
          Reflect.instantiate(clazz, args).asInstanceOf[IndirectActorProducer]
      }
    } else if (classOf[Actor].isAssignableFrom(clazz)) {
      if (args.isEmpty) new NoArgsReflectConstructor(clazz.asInstanceOf[Class[_ <: Actor]])
      else new ArgsReflectConstructor(clazz.asInstanceOf[Class[_ <: Actor]], args)
    } else throw new IllegalArgumentException(s"unknown actor creator [$clazz]")
  }
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
private[akka] class CreatorConsumer(clazz: Class[_ <: Actor], creator: Creator[Actor]) extends IndirectActorProducer {
  override def actorClass = clazz
  override def produce() = creator.create()
}

/**
 * INTERNAL API
 */
private[akka] class TypedCreatorFunctionConsumer(clz: Class[_ <: Actor], creator: () ⇒ Actor) extends IndirectActorProducer {
  override def actorClass = clz
  override def produce() = creator()
}

/**
 * INTERNAL API
 */
private[akka] class ArgsReflectConstructor(clz: Class[_ <: Actor], args: immutable.Seq[Any]) extends IndirectActorProducer {
  private[this] val constructor: Constructor[_] = Reflect.findConstructor(clz, args)
  override def actorClass = clz
  override def produce() = Reflect.instantiate(constructor, args).asInstanceOf[Actor]
}

/**
 * INTERNAL API
 */
private[akka] class NoArgsReflectConstructor(clz: Class[_ <: Actor]) extends IndirectActorProducer {
  Reflect.findConstructor(clz, List.empty)
  override def actorClass = clz
  override def produce() = Reflect.instantiate(clz)
}
