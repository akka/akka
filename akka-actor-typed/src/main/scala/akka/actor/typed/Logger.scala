/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import java.util.Optional

import akka.annotation.{ DoNotInherit, InternalApi }
import org.slf4j.Logger
import org.slf4j.event.Level

/**
 * A log marker is an additional metadata tag supported by some logging backends to identify "special" log events.
 * In the Akka internal actors for example the "SECURITY" marker is used for warnings related to security.
 *
 * Not for user extension, create instances using factory methods
 */
@DoNotInherit
sealed trait LogMarker {
  def name: String
}

/**
 * Factories for log markers
 */
object LogMarker {

  /**
   * INTERNAL API
   */
  @InternalApi
  private final class LogMarkerImpl(name: String) extends akka.event.LogMarker(name) with LogMarker

  /**
   * Scala API: Create a new log marker with the given name
   */
  def apply(name: String): LogMarker = new LogMarkerImpl(name)

  /**
   * Scala API: Create a new log marker with the given name
   */
  def create(name: String): LogMarker = apply(name)

}

/**
 * Logging options when using `Behaviors.logMessages`.
 */
@DoNotInherit
abstract sealed class LogOptions {

  /**
   * User control whether messages are logged or not.  This is useful when you want to have an application configuration
   * to control when to log messages.
   */
  def withEnabled(enabled: Boolean): LogOptions

  /**
   * The [[akka.event.Logging.LogLevel]] to use when logging messages.
   */
  def withLevel(level: Level): LogOptions

  /**
   * A [[org.slf4j.Logger]] to use when logging messages.
   */
  def withLogger(logger: Logger): LogOptions

  def enabled: Boolean
  def level: Level
  def logger: Option[Logger]

  /** Java API */
  def getLogger: Optional[Logger]
}

/**
 * Factories for log options
 */
object LogOptions {

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] final case class LogOptionsImpl(enabled: Boolean, level: Level, logger: Option[Logger])
      extends LogOptions {

    /**
     * User control whether messages are logged or not.  This is useful when you want to have an application configuration
     * to control when to log messages.
     */
    override def withEnabled(enabled: Boolean): LogOptions = this.copy(enabled = enabled)

    /**
     * The [[akka.event.Logging.LogLevel]] to use when logging messages.
     */
    override def withLevel(level: Level): LogOptions = this.copy(level = level)

    /**
     * A [[org.slf4j.Logger]] to use when logging messages.
     */
    override def withLogger(logger: Logger): LogOptions = this.copy(logger = Option(logger))

    /** Java API */
    override def getLogger: Optional[Logger] = Optional.ofNullable(logger.orNull)
  }

  /**
   * Scala API: Create a new log options with defaults.
   */
  def apply(): LogOptions = LogOptionsImpl(enabled = true, Level.DEBUG, None)

  /**
   * Java API: Create a new log options.
   */
  def create(): LogOptions = apply()
}
