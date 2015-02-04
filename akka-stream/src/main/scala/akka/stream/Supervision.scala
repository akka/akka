/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.util.control.NonFatal
import akka.stream.javadsl.japi

object Supervision {
  sealed trait Directive

  /**
   * The stream will be completed with failure if application code for processing an element
   * throws an exception.
   */
  case object Stop extends Directive

  /**
   * Java API: The stream will be completed with failure if application code for processing an element
   * throws an exception.
   */
  def stop = Stop

  /**
   * The element is dropped and the stream continues if application code for processing
   * an element throws an exception.
   */
  case object Resume extends Directive

  /**
   * Java API: The element is dropped and the stream continues if application code for processing
   * an element throws an exception.
   */
  def resume = Resume

  /**
   * The element is dropped and the stream continues after restarting the stage
   * if application code for processing an element throws an exception.
   * Restarting a stage means that any accumulated state is cleared. This is typically
   * performed by creating a new instance of the stage.
   */
  case object Restart extends Directive

  /**
   * Java API: The element is dropped and the stream continues after restarting the stage
   * if application code for processing an element throws an exception.
   * Restarting a stage means that any accumulated state is cleared. This is typically
   * performed by creating a new instance of the stage.
   */
  def restart = Restart

  type Decider = Function[Throwable, Directive]

  /**
   * Scala API: [[Decider]] that returns [[Stop]] for all exceptions.
   */
  val stoppingDecider: Decider = {
    case NonFatal(_) ⇒ Stop
  }

  /**
   * Java API: Decider function that returns [[#stop]] for all exceptions.
   */
  val getStoppingDecider: japi.Function[Throwable, Directive] =
    new japi.Function[Throwable, Directive] {
      override def apply(e: Throwable): Directive = stoppingDecider(e)
    }

  /**
   * Scala API: [[Decider]] that returns [[Resume]] for all exceptions.
   */
  val resumingDecider: Decider = {
    case NonFatal(_) ⇒ Resume
  }

  /**
   * Java API: Decider function that returns [[#resume]] for all exceptions.
   */
  val getResumingDecider: japi.Function[Throwable, Directive] =
    new japi.Function[Throwable, Directive] {
      override def apply(e: Throwable): Directive = resumingDecider(e)
    }

  /**
   * Scala API: [[Decider]] that returns [[Restart]] for all exceptions.
   */
  val restartingDecider: Decider = {
    case NonFatal(_) ⇒ Restart
  }

  /**
   * Java API: Decider function that returns [[#restart]] for all exceptions.
   */
  val getRestartingDecider: japi.Function[Throwable, Directive] =
    new japi.Function[Throwable, Directive] {
      override def apply(e: Throwable): Directive = restartingDecider(e)
    }

}
