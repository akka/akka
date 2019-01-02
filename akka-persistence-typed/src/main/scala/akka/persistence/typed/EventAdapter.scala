/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.annotation.InternalApi

abstract class EventAdapter[E, P] {
  /**
   * Type of the event to persist
   */
  type Per = P
  /**
   * Transform event on the way to the journal
   */
  def toJournal(e: E): Per

  /**
   * Transform the event on recovery from the journal.
   * Note that this is not called in any read side so will need to be applied
   * manually when using Query.
   */
  def fromJournal(p: Per): E
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object NoOpEventAdapter {
  private val i = new NoOpEventAdapter[Nothing]
  def instance[E]: NoOpEventAdapter[E] = i.asInstanceOf[NoOpEventAdapter[E]]
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class NoOpEventAdapter[E] extends EventAdapter[E, Any] {
  override def toJournal(e: E): Any = e
  override def fromJournal(p: Any): E = p.asInstanceOf[E]
}

