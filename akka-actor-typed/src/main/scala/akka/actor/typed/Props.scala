/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi

import scala.annotation.tailrec
import scala.reflect.ClassTag

object Props {

  /**
   * Empty props instance, should seldom be needed in user code but can be useful as a default props where
   * custom configuration of an actor is possible.
   */
  val empty: Props = EmptyProps
}

/**
 * Data structure for describing an actor’s props details like which
 * executor to run it on. For each type of setting (e.g. [[DispatcherSelector]])
 * the FIRST occurrence is used when creating the
 * actor; this means that adding configuration using the "with" methods
 * overrides what was configured previously.
 *
 * Deliberately not sealed in order to emphasize future extensibility by the
 * framework—this is not intended to be extended by user code.
 *
 * Not for user extension.
 */
@DoNotInherit
@ApiMayChange
abstract class Props private[akka] () extends Product with Serializable {

  /**
   * Reference to the tail of this Props list.
   *
   * The `next` reference is here so that it can form an
   * internally linked list. Traversal of this list stops when encountering the
   * [[EmptyProps]] object.
   *
   * INTERNAL API
   */
  @InternalApi
  private[akka] def next: Props

  /**
   * Create a copy of this Props node with its `next` reference
   * replaced by the given object. <b>This does NOT append the given list
   * of configuration nodes to the current list!</b>
   *
   * INTERNAL API
   */
  @InternalApi
  private[akka] def withNext(next: Props): Props

  /**
   * Prepend a selection of the [[ActorSystem]] default executor to this Props.
   */
  def withDispatcherDefault: Props = DispatcherDefault(this)

  /**
   * Prepend a selection of the executor found at the given Config path to this Props.
   * The path is relative to the configuration root of the [[ActorSystem]] that looks up the
   * executor.
   */
  def withDispatcherFromConfig(path: String): Props = DispatcherFromConfig(path, this)

  /**
   * Find the first occurrence of a configuration node of the given type, falling
   * back to the provided default if none is found.
   *
   * INTERNAL API
   */
  @InternalApi
  private[akka] def firstOrElse[T <: Props: ClassTag](default: T): T = {
    @tailrec def rec(d: Props): T = {
      d match {
        case EmptyProps => default
        case t: T       => t
        case _          => rec(d.next)
      }
    }
    rec(this)
  }

  /**
   * Retrieve all configuration nodes of a given type in the order that they
   * are present in this Props. The `next` reference for all returned
   * nodes will be the [[EmptyProps]].
   *
   * INTERNAL API
   */
  @InternalApi
  private[akka] def allOf[T <: Props: ClassTag]: List[Props] = {
    @tailrec def select(d: Props, acc: List[Props]): List[Props] =
      d match {
        case EmptyProps => acc.reverse
        case _: T       => select(d.next, (d.withNext(EmptyProps)) :: acc)
        case _          => select(d.next, acc)
      }
    select(this, Nil)
  }

  /**
   * Remove all configuration nodes of a given type and return the resulting
   * Props.
   */
  @InternalApi
  private[akka] def filterNot[T <: Props: ClassTag]: Props = {
    @tailrec def select(d: Props, acc: List[Props]): List[Props] =
      d match {
        case EmptyProps => acc
        case _: T       => select(d.next, acc)
        case _          => select(d.next, d :: acc)
      }
    @tailrec def link(l: List[Props], acc: Props): Props =
      l match {
        case d :: ds => link(ds, d.withNext(acc))
        case Nil     => acc
      }
    link(select(this, Nil), EmptyProps)
  }
}

/**
 * The empty configuration node, used as a terminator for the internally linked
 * list of each Props.
 */
@InternalApi
private[akka] case object EmptyProps extends Props {
  override def next = throw new NoSuchElementException("EmptyProps has no next")
  override def withNext(next: Props): Props = next
}

/**
 * Not for user extension.
 */
@DoNotInherit
sealed abstract class DispatcherSelector extends Props

/**
 * Factories for [[DispatcherSelector]]s which describe which thread pool shall be used to run
 * the actor to which this configuration is applied. Se the individual factory methods for details
 * on the options.
 *
 * The default configuration if none of these options are present is to run
 * the actor on the default [[ActorSystem]] executor.
 */
object DispatcherSelector {

  /**
   * Scala API:
   * Run the actor on the default [[ActorSystem]] executor.
   */
  def default(): DispatcherSelector = DispatcherDefault()

  /**
   * Java API:
   * Run the actor on the default [[ActorSystem]] executor.
   */
  def defaultDispatcher(): DispatcherSelector = default()

  /**
   *  Run the actor on the default blocking dispatcher that is
   *  configured under default-blocking-io-dispatcher
   */
  def blocking(): DispatcherSelector = fromConfig("akka.actor.default-blocking-io-dispatcher")

  /**
   * Look up an executor definition in the [[ActorSystem]] configuration.
   * ExecutorServices created in this fashion will be shut down when the
   * ActorSystem terminates.
   */
  def fromConfig(path: String): DispatcherSelector = DispatcherFromConfig(path)
}

/**
 * INTERNAL API
 *
 * Use the [[ActorSystem]] default executor to run the actor.
 */
@DoNotInherit
@InternalApi
private[akka] sealed case class DispatcherDefault(next: Props) extends DispatcherSelector {
  @InternalApi
  override def withNext(next: Props): Props = copy(next = next)
}
object DispatcherDefault {
  // this is hidden in order to avoid having people match on this object
  private val empty = DispatcherDefault(EmptyProps)

  /**
   * Retrieve an instance for this configuration node with empty `next` reference.
   */
  def apply(): DispatcherDefault = empty
}

/**
 * Look up an executor definition in the [[ActorSystem]] configuration.
 * ExecutorServices created in this fashion will be shut down when the
 * ActorSystem terminates.
 *
 * INTERNAL API
 */
@InternalApi
private[akka] final case class DispatcherFromConfig(path: String, next: Props = Props.empty)
    extends DispatcherSelector {
  override def withNext(next: Props): Props = copy(next = next)
}
