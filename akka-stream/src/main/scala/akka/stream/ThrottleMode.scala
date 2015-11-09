/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

/**
 * Represents a mode that decides how to deal exceed rate for Throttle combinator
 */
sealed abstract class ThrottleMode

object ThrottleMode {

  /**
   *  Tells throttle to make pauses before emitting messages to meet throttle rate
   */
  case object Shaping extends ThrottleMode

  /**
   * Makes throttle fail with exception when upstream is faster than throttle rate
   */
  case object Enforcing extends ThrottleMode

}

/**
 * Exception that is thrown when rated controlled by stream is exceeded
 */
class RateExceededException(msg: String) extends RuntimeException(msg)
