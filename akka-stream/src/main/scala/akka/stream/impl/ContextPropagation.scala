/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] trait ContextPropagation {
  def suspendContext(): Unit
  def resumeContext(): Unit
  def currentContext(): AnyRef
  def resumeContext(context: AnyRef): Unit
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ContextPropagation {

  /**
   * INTERNAL API
   */
  @InternalApi def apply(): ContextPropagation = new ContextPropagationImpl
}

private[akka] final class ContextPropagationImpl extends ContextPropagation {
  def suspendContext(): Unit = ()
  def resumeContext(): Unit = ()
  def currentContext(): AnyRef = null
  def resumeContext(context: AnyRef): Unit = ()
}
