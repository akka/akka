/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed

import java.util.Optional

import akka.actor.typed.LogMarker
import akka.event.Logging.LogLevel
import akka.util.OptionVal
import akka.util.OptionVal.{ None, Some }

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._
import scala.{ None ⇒ SNone, Option ⇒ SOption, Some ⇒ SSome }

/**
 * Representation of a Log Event issued by a [[akka.actor.typed.Behavior]]
 */
final case class CapturedLogEvent private[akka] (logLevel: LogLevel, message: String,
                                                 private val cause:  OptionVal[Throwable],
                                                 private val marker: OptionVal[LogMarker],
                                                 mdc:                Map[String, Any]) {

  /**
   * Constructor for Java code
   */
  def this(logLevel: LogLevel, message: String, errorCause: Optional[Throwable], marker: Optional[LogMarker], mdc: java.util.Map[String, Any]) {
    this(logLevel, message, errorCause.asScala.map(Some(_)).getOrElse(None), marker.asScala.map(Some(_)).getOrElse(None), mdc.asScala.toMap)
  }

  def getMdc: java.util.Map[String, Any] = mdc.asJava

  //This is needed as OptionVal is private for the akka package
  def errorCause: SOption[Throwable] = cause match {
    case Some(ex) ⇒ SSome(ex)
    case _        ⇒ SNone
  }

  def getErrorCause: Optional[Throwable] = errorCause.asJava

  //This is needed as OptionVal is private for the akka package
  def logMarker: SOption[LogMarker] = marker match {
    case Some(logMarker) ⇒ SSome(logMarker)
    case _               ⇒ SNone
  }

  def getLogMarker: Optional[LogMarker] = logMarker.asJava
}

object CapturedLogEvent {

  /**
   * This auxiliary constructor uses Scala's Option instead, so it will create extra allocations. This, however, should
   * not be critical as it is only used in the [[akka.actor.testkit.typed.scaladsl.BehaviorTestKit]]
   */
  def apply(
    logLevel:   LogLevel,
    message:    String,
    errorCause: SOption[Throwable] = SNone,
    logMarker:  SOption[LogMarker] = SNone,
    mdc:        Map[String, Any]   = Map.empty): CapturedLogEvent = {
    new CapturedLogEvent(logLevel, message, errorCause.map(Some(_)).getOrElse(None), logMarker.map(Some(_)).getOrElse(None), mdc)
  }
}
