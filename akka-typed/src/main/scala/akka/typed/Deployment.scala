/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor }
import java.util.concurrent.{ Executor, Executors }
import scala.reflect.ClassTag
import scala.annotation.tailrec

/**
 * Data structure for describing an actor’s deployment details like which
 * executor to run it on.
 *
 * Deliberately not sealed in order to emphasize future extensibility by the
 * framework—this is not intended to be extended by user code.
 */
abstract class DeploymentConfig extends Product with Serializable {
  def next: DeploymentConfig
  def ++(next: DeploymentConfig): DeploymentConfig

  def withDispatcherDefault: DeploymentConfig = DispatcherDefault(this)
  def withDispatcherFromConfig(path: String): DeploymentConfig = DispatcherFromConfig(path, this)
  def withDispatcherFromExecutor(executor: Executor): DeploymentConfig = DispatcherFromExecutor(executor, this)
  def withDispatcherFromExecutionContext(ec: ExecutionContext): DeploymentConfig = DispatcherFromExecutionContext(ec, this)

  def withMailboxCapacity(capacity: Int): DeploymentConfig = MailboxCapacity(capacity, this)

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

  def allOf[T <: DeploymentConfig: ClassTag]: List[DeploymentConfig] = {
    @tailrec def select(d: DeploymentConfig, acc: List[DeploymentConfig]): List[DeploymentConfig] =
      d match {
        case EmptyDeploymentConfig ⇒ acc.reverse
        case t: T                  ⇒ select(d.next, (d ++ EmptyDeploymentConfig) :: acc)
        case _                     ⇒ select(d.next, acc)
      }
    select(this, Nil)
  }

  def filterNot[T <: DeploymentConfig: ClassTag]: DeploymentConfig = {
    @tailrec def select(d: DeploymentConfig, acc: List[DeploymentConfig]): List[DeploymentConfig] =
      d match {
        case EmptyDeploymentConfig ⇒ acc
        case t: T                  ⇒ select(d.next, acc)
        case _                     ⇒ select(d.next, d :: acc)
      }
    @tailrec def link(l: List[DeploymentConfig], acc: DeploymentConfig): DeploymentConfig =
      l match {
        case d :: ds ⇒ link(ds, d ++ acc)
        case Nil     ⇒ acc
      }
    link(select(this, Nil), EmptyDeploymentConfig)
  }
}

final case class MailboxCapacity(capacity: Int, next: DeploymentConfig = EmptyDeploymentConfig) extends DeploymentConfig {
  override def ++(next: DeploymentConfig): DeploymentConfig = copy(next = next)
}

case object EmptyDeploymentConfig extends DeploymentConfig {
  override def next = throw new NoSuchElementException("EmptyDeploymentConfig has no next")
  override def ++(next: DeploymentConfig): DeploymentConfig = next
}

sealed trait DispatcherSelector extends DeploymentConfig

sealed case class DispatcherDefault(next: DeploymentConfig) extends DispatcherSelector {
  override def ++(next: DeploymentConfig): DeploymentConfig = copy(next = next)
}
object DispatcherDefault {
  // this is hidden in order to avoid having people match on this object
  private val empty = DispatcherDefault(EmptyDeploymentConfig)
  def apply(): DispatcherDefault = empty
}
final case class DispatcherFromConfig(path: String, next: DeploymentConfig = EmptyDeploymentConfig) extends DispatcherSelector {
  override def ++(next: DeploymentConfig): DeploymentConfig = copy(next = next)
}
final case class DispatcherFromExecutor(executor: Executor, next: DeploymentConfig = EmptyDeploymentConfig) extends DispatcherSelector {
  override def ++(next: DeploymentConfig): DeploymentConfig = copy(next = next)
}
final case class DispatcherFromExecutionContext(ec: ExecutionContext, next: DeploymentConfig = EmptyDeploymentConfig) extends DispatcherSelector {
  override def ++(next: DeploymentConfig): DeploymentConfig = copy(next = next)
}

trait Dispatchers {
  def lookup(selector: DispatcherSelector): ExecutionContextExecutor
  def shutdown(): Unit
}
