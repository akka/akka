/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import java.util.concurrent.Executor
import scala.concurrent.ExecutionContext

sealed trait DispatcherSelector
case object DispatcherDefault extends DispatcherSelector
final case class DispatcherFromConfig(path: String) extends DispatcherSelector
final case class DispatcherFromExecutor(executor: Executor) extends DispatcherSelector
final case class DispatcherFromExecutionContext(ec: ExecutionContext) extends DispatcherSelector

/**
 * Props describe how to dress up a [[Behavior]] so that it can become an Actor.
 */
final case class Props[T](creator: () ⇒ Behavior[T], dispatcher: DispatcherSelector, mailboxCapacity: Int) {
  def withDispatcher(configPath: String) = copy(dispatcher = DispatcherFromConfig(configPath))
  def withDispatcher(executor: Executor) = copy(dispatcher = DispatcherFromExecutor(executor))
  def withDispatcher(ec: ExecutionContext) = copy(dispatcher = DispatcherFromExecutionContext(ec))
  def withQueueSize(size: Int) = copy(mailboxCapacity = size)
}

/**
 * Props describe how to dress up a [[Behavior]] so that it can become an Actor.
 */
object Props {
  /**
   * Create a Props instance from a block of code that creates a [[Behavior]].
   *
   * FIXME: investigate the pros and cons of making this take an explicit
   *        function instead of a by-name argument
   */
  def apply[T](block: ⇒ Behavior[T]): Props[T] = Props(() ⇒ block, DispatcherDefault, Int.MaxValue)

  /**
   * Props for a Behavior that just ignores all messages.
   */
  def empty[T]: Props[T] = _empty.asInstanceOf[Props[T]]
  private val _empty: Props[Any] = Props(ScalaDSL.Static[Any] { case _ ⇒ ScalaDSL.Unhandled })

}
