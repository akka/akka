/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor }
import java.util.concurrent.{ Executor, Executors }
import scala.reflect.ClassTag
import scala.annotation.tailrec

/**
 * Data structure for describing an actor’s deployment details like which
 * executor to run it on. For each type of setting (e.g. [[DispatcherSelector]]
 * or [[MailboxCapacity]]) the FIRST occurrence is used when creating the
 * actor; this means that adding configuration using the "with" methods
 * overrides what was configured previously.
 *
 * Deliberately not sealed in order to emphasize future extensibility by the
 * framework—this is not intended to be extended by user code.
 *
 * The DeploymentConfig includes a `next` reference so that it can form an
 * internally linked list. Traversal of this list stops when encountering the
 * [[EmptyDeploymentConfig$]] object.
 */
abstract class DeploymentConfig extends Product with Serializable {
  /**
   * Reference to the tail of this DeploymentConfig list.
   */
  def next: DeploymentConfig

  /**
   * Create a copy of this DeploymentConfig node with its `next` reference
   * replaced by the given object. <b>This does NOT append the given list
   * of configuration nodes to the current list!</b>
   */
  def withNext(next: DeploymentConfig): DeploymentConfig

  /**
   * Prepend a selection of the [[ActorSystem]] default executor to this DeploymentConfig.
   */
  def withDispatcherDefault: DeploymentConfig = DispatcherDefault(this)

  /**
   * Prepend a selection of the executor found at the given Config path to this DeploymentConfig.
   * The path is relative to the configuration root of the [[ActorSystem]] that looks up the
   * executor.
   */
  def withDispatcherFromConfig(path: String): DeploymentConfig = DispatcherFromConfig(path, this)

  /**
   * Prepend a selection of the given executor to this DeploymentConfig.
   */
  def withDispatcherFromExecutor(executor: Executor): DeploymentConfig = DispatcherFromExecutor(executor, this)

  /**
   * Prepend a selection of the given execution context to this DeploymentConfig.
   */
  def withDispatcherFromExecutionContext(ec: ExecutionContext): DeploymentConfig = DispatcherFromExecutionContext(ec, this)

  /**
   * Prepend the given mailbox capacity configuration to this DeploymentConfig.
   */
  def withMailboxCapacity(capacity: Int): DeploymentConfig = MailboxCapacity(capacity, this)

  /**
   * Find the first occurrence of a configuration node of the given type, falling
   * back to the provided default if none is found.
   */
  def firstOrElse[T <: DeploymentConfig: ClassTag](default: T): T = {
    @tailrec def rec(d: DeploymentConfig): T = {
      d match {
        case EmptyDeploymentConfig ⇒ default
        case t: T                  ⇒ t
        case _                     ⇒ rec(d.next)
      }
    }
    rec(this)
  }

  /**
   * Retrieve all configuration nodes of a given type in the order that they
   * are present in this DeploymentConfig. The `next` reference for all returned
   * nodes will be the [[EmptyDeploymentConfig$]].
   */
  def allOf[T <: DeploymentConfig: ClassTag]: List[DeploymentConfig] = {
    @tailrec def select(d: DeploymentConfig, acc: List[DeploymentConfig]): List[DeploymentConfig] =
      d match {
        case EmptyDeploymentConfig ⇒ acc.reverse
        case t: T                  ⇒ select(d.next, (d withNext EmptyDeploymentConfig) :: acc)
        case _                     ⇒ select(d.next, acc)
      }
    select(this, Nil)
  }

  /**
   * Remove all configuration nodes of a given type and return the resulting
   * DeploymentConfig.
   */
  def filterNot[T <: DeploymentConfig: ClassTag]: DeploymentConfig = {
    @tailrec def select(d: DeploymentConfig, acc: List[DeploymentConfig]): List[DeploymentConfig] =
      d match {
        case EmptyDeploymentConfig ⇒ acc
        case t: T                  ⇒ select(d.next, acc)
        case _                     ⇒ select(d.next, d :: acc)
      }
    @tailrec def link(l: List[DeploymentConfig], acc: DeploymentConfig): DeploymentConfig =
      l match {
        case d :: ds ⇒ link(ds, d withNext acc)
        case Nil     ⇒ acc
      }
    link(select(this, Nil), EmptyDeploymentConfig)
  }
}

/**
 * Configure the maximum mailbox capacity for the actor. If more messages are
 * enqueued because the actor does not process them quickly enough then further
 * messages will be dropped.
 *
 * The default mailbox capacity that is used when this option is not given is
 * taken from the `akka.typed.mailbox-capacity` configuration setting.
 */
final case class MailboxCapacity(capacity: Int, next: DeploymentConfig = EmptyDeploymentConfig) extends DeploymentConfig {
  override def withNext(next: DeploymentConfig): DeploymentConfig = copy(next = next)
}

/**
 * The empty configuration node, used as a terminator for the internally linked
 * list of each DeploymentConfig.
 */
case object EmptyDeploymentConfig extends DeploymentConfig {
  override def next = throw new NoSuchElementException("EmptyDeploymentConfig has no next")
  override def withNext(next: DeploymentConfig): DeploymentConfig = next
}

/**
 * Subclasses of this type describe which thread pool shall be used to run
 * the actor to which this configuration is applied.
 *
 * The default configuration if none of these options are present is to run
 * the actor on the same executor as its parent.
 */
sealed trait DispatcherSelector extends DeploymentConfig

/**
 * Use the [[ActorSystem]] default executor to run the actor.
 */
sealed case class DispatcherDefault(next: DeploymentConfig) extends DispatcherSelector {
  override def withNext(next: DeploymentConfig): DeploymentConfig = copy(next = next)
}
object DispatcherDefault {
  // this is hidden in order to avoid having people match on this object
  private val empty = DispatcherDefault(EmptyDeploymentConfig)
  /**
   * Retrieve an instance for this configuration node with empty `next` reference.
   */
  def apply(): DispatcherDefault = empty
}

/**
 * Look up an executor definition in the [[ActorSystem]] configuration.
 * ExecutorServices created in this fashion will be shut down when the
 * ActorSystem terminates.
 */
final case class DispatcherFromConfig(path: String, next: DeploymentConfig = EmptyDeploymentConfig) extends DispatcherSelector {
  override def withNext(next: DeploymentConfig): DeploymentConfig = copy(next = next)
}

/**
 * Directly use the given Executor whenever the actor needs to be run.
 * No attempt will be made to shut down this thread pool, even if it is an
 * instance of ExecutorService.
 */
final case class DispatcherFromExecutor(executor: Executor, next: DeploymentConfig = EmptyDeploymentConfig) extends DispatcherSelector {
  override def withNext(next: DeploymentConfig): DeploymentConfig = copy(next = next)
}

/**
 * Directly use the given ExecutionContext whenever the actor needs to be run.
 * No attempt will be made to shut down this thread pool, even if it is an
 * instance of ExecutorService.
 */
final case class DispatcherFromExecutionContext(ec: ExecutionContext, next: DeploymentConfig = EmptyDeploymentConfig) extends DispatcherSelector {
  override def withNext(next: DeploymentConfig): DeploymentConfig = copy(next = next)
}
