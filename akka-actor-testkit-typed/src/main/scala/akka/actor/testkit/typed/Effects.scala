/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed

import akka.actor.testkit.typed.scaladsl.BehaviorTestKit
import akka.actor.typed.{ ActorRef, Behavior, Props }
import akka.annotation.InternalApi

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.compat.java8.FunctionConverters._

/**
 * Types for behavior effects for [[BehaviorTestKit]], each effect has a suitable equals and can be used to compare
 * actual effects to expected ones.
 */
object Effects {

  /**
   * The behavior spawned a named child with the given behavior (and optionally specific props)
   */
  final class Spawned[T](val behavior: Behavior[T], val childName: String, val props: Props, val ref: ActorRef[T])
    extends Effect with Product3[Behavior[T], String, Props] with Serializable {

    override def equals(other: Any) = other match {
      case o: Spawned[_] ⇒
        this.behavior == o.behavior &&
          this.childName == o.childName &&
          this.props == o.props
      case _ ⇒ false
    }
    override def hashCode: Int = (behavior.## * 31 + childName.##) * 31 + props.##
    override def toString: String = s"Spawned($behavior, $childName, $props)"

    override def productPrefix = "Spawned"
    override def _1: Behavior[T] = behavior
    override def _2: String = childName
    override def _3: Props = props
    override def canEqual(o: Any) = o.isInstanceOf[Spawned[_]]
  }
  object Spawned {
    def apply[T](behavior: Behavior[T], childName: String, props: Props = Props.empty): Spawned[T] = new Spawned(behavior, childName, props, null)
    def unapply[T](s: Spawned[T]): Option[(Behavior[T], String, Props)] = Some((s.behavior, s.childName, s.props))
  }

  /**
   * The behavior spawned an anonymous child with the given behavior (and optionally specific props)
   */
  final class SpawnedAnonymous[T](val behavior: Behavior[T], val props: Props, val ref: ActorRef[T])
    extends Effect with Product2[Behavior[T], Props] with Serializable {

    override def equals(other: Any) = other match {
      case o: SpawnedAnonymous[_] ⇒ this.behavior == o.behavior && this.props == o.props
      case _                      ⇒ false
    }
    override def hashCode: Int = behavior.## * 31 + props.##
    override def toString: String = s"SpawnedAnonymous($behavior, $props)"

    override def productPrefix = "SpawnedAnonymous"
    override def _1: Behavior[T] = behavior
    override def _2: Props = props
    override def canEqual(o: Any) = o.isInstanceOf[SpawnedAnonymous[_]]
  }

  object SpawnedAnonymous {
    def apply[T](behavior: Behavior[T], props: Props = Props.empty): SpawnedAnonymous[T] = new SpawnedAnonymous(behavior, props, null)
    def unapply[T](s: SpawnedAnonymous[T]): Option[(Behavior[T], Props)] = Some((s.behavior, s.props))
  }

  /**
   * INTERNAL API
   * Spawning adapters is private[akka]
   */
  @InternalApi
  private[akka] final class SpawnedAdapter[T](val name: String, val ref: ActorRef[T])
    extends Effect with Product1[String] with Serializable {

    override def equals(other: Any) = other match {
      case o: SpawnedAdapter[_] ⇒ this.name == o.name
      case _                    ⇒ false
    }
    override def hashCode: Int = name.##
    override def toString: String = s"SpawnedAdapter($name)"

    override def productPrefix = "SpawnedAdapter"
    override def _1: String = name
    override def canEqual(o: Any) = o.isInstanceOf[SpawnedAdapter[_]]
  }

  /**
   * INTERNAL API
   * Spawning adapters is private[akka]
   */
  @InternalApi
  private[akka] object SpawnedAdapter {
    def apply[T](name: String): SpawnedAdapter[T] = new SpawnedAdapter(name, null)
    def unapply[T](s: SpawnedAdapter[T]): Option[Tuple1[String]] = Some(Tuple1(s.name))
  }

  /**
   * The behavior spawned an anonymous adapter, through `ctx.spawnMessageAdapter`
   */
  final class SpawnedAnonymousAdapter[T](val ref: ActorRef[T])
    extends Effect with Product with Serializable {

    override def equals(other: Any) = other match {
      case _: SpawnedAnonymousAdapter[_] ⇒ true
      case _                             ⇒ false
    }
    override def hashCode: Int = Nil.##
    override def toString: String = "SpawnedAnonymousAdapter"

    override def productPrefix = "SpawnedAnonymousAdapter"
    override def productIterator = Iterator.empty
    override def productArity = 0
    override def productElement(n: Int) = throw new NoSuchElementException
    override def canEqual(o: Any) = o.isInstanceOf[SpawnedAnonymousAdapter[_]]
  }
  object SpawnedAnonymousAdapter {
    def apply[T]() = new SpawnedAnonymousAdapter[T](null)
    def unapply[T](s: SpawnedAnonymousAdapter[T]): Boolean = true
  }
  /**
   * The behavior create a message adapter for the messages of type clazz
   * FIXME, function as a java api?
   */
  final case class MessageAdapter[A, T](messageClass: Class[A], adapt: A ⇒ T) extends Effect {
    /**
     * JAVA API
     */
    def adaptFunction: java.util.function.Function[A, T] = adapt.asJava
  }
  /**
   * The behavior stopped `childName`
   */
  final case class Stopped(childName: String) extends Effect
  /**
   * The behavior started watching `other`, through `ctx.watch(other)`
   */
  final case class Watched[T](other: ActorRef[T]) extends Effect

  /**
   * The behavior started watching `other`, through `ctx.unwatch(other)`
   */
  final case class Unwatched[T](other: ActorRef[T]) extends Effect
  /**
   * The behavior set a new receive timeout, with `msg` as timeout notification
   */
  final case class ReceiveTimeoutSet[T](d: Duration, msg: T) extends Effect

  /**
   * The behavior used `ctx.schedule` to schedule `msg` to be sent to `target` after `delay`
   * FIXME what about events scheduled through the scheduler?
   */
  final case class Scheduled[U](delay: FiniteDuration, target: ActorRef[U], msg: U) extends Effect

  /**
   * Used to represent an empty list of effects - in other words, the behavior didn't do anything observable
   */
  case object NoEffects extends NoEffects

  /**
   * Used for NoEffects expectations by type
   */
  sealed abstract class NoEffects extends Effect
}
