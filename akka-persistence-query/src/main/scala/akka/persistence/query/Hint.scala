/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

/**
 * A query hint that defines how to execute the query,
 * typically specific to the journal implementation.
 *
 * A plugin may optionally support a [[Hint]].
 */
trait Hint

/**
 * If the underlying datastore only supports queries that are completed when they reach the
 * end of the "result set", the journal has to submit new queries after a while in order
 * to support "infinite" event streams that include events stored after the initial query has completed.
 *
 * A plugin may optionally support this [[Hint]] for defining such a refresh interval.
 */
final case class RefreshInterval(interval: FiniteDuration) extends Hint
object RefreshInterval {
  /** Java API */
  def create(length: Long, unit: TimeUnit): RefreshInterval = new RefreshInterval(FiniteDuration(length, unit))
  /** Java API */
  def create(interval: FiniteDuration): RefreshInterval = new RefreshInterval(interval)
}

/**
 * Indicates that the event stream is supposed to be completed immediately when it
 * reaches the end of the "result set", as described in [[RefreshInterval]].
 *
 */
final case object NoRefresh extends NoRefresh {
  /** Java API */
  def getInstance: NoRefresh = this
}
sealed class NoRefresh extends Hint

