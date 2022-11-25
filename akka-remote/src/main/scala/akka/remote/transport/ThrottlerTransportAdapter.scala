/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.transport

import java.util.concurrent.TimeUnit

import scala.annotation.nowarn
import scala.concurrent.duration._
import scala.math.min

import akka.actor._

// FIXME change this?
// not deprecating this because Direction is widely used, we can change testkit anyway when removing classic
object ThrottlerTransportAdapter {
  val SchemeIdentifier = "trttl"
  val UniqueId = new java.util.concurrent.atomic.AtomicInteger(0)

  sealed trait Direction {
    def includes(other: Direction): Boolean
  }

  object Direction {

    @SerialVersionUID(1L)
    case object Send extends Direction {
      override def includes(other: Direction): Boolean = other match {
        case Send => true
        case _    => false
      }

      /**
       * Java API: get the singleton instance
       */
      def getInstance = this
    }

    @SerialVersionUID(1L)
    case object Receive extends Direction {
      override def includes(other: Direction): Boolean = other match {
        case Receive => true
        case _       => false
      }

      /**
       * Java API: get the singleton instance
       */
      def getInstance = this
    }

    @SerialVersionUID(1L)
    case object Both extends Direction {
      override def includes(other: Direction): Boolean = true

      /**
       * Java API: get the singleton instance
       */
      def getInstance = this
    }
  }

  @SerialVersionUID(1L)
  final case class SetThrottle(address: Address, direction: Direction, mode: ThrottleMode)

  @SerialVersionUID(1L)
  case object SetThrottleAck {

    /**
     * Java API: get the singleton instance
     */
    def getInstance = this
  }

  sealed trait ThrottleMode extends NoSerializationVerificationNeeded {
    def tryConsumeTokens(nanoTimeOfSend: Long, tokens: Int): (ThrottleMode, Boolean)
    def timeToAvailable(currentNanoTime: Long, tokens: Int): FiniteDuration
  }

  @SerialVersionUID(1L)
  final case class TokenBucket(capacity: Int, tokensPerSecond: Double, nanoTimeOfLastSend: Long, availableTokens: Int)
      extends ThrottleMode {

    private def isAvailable(nanoTimeOfSend: Long, tokens: Int): Boolean =
      if ((tokens > capacity && availableTokens > 0)) {
        true // Allow messages larger than capacity through, it will be recorded as negative tokens
      } else min((availableTokens + tokensGenerated(nanoTimeOfSend)), capacity) >= tokens

    override def tryConsumeTokens(nanoTimeOfSend: Long, tokens: Int): (ThrottleMode, Boolean) = {
      if (isAvailable(nanoTimeOfSend, tokens))
        (
          this.copy(
            nanoTimeOfLastSend = nanoTimeOfSend,
            availableTokens = min(availableTokens - tokens + tokensGenerated(nanoTimeOfSend), capacity)),
          true)
      else (this, false)
    }

    override def timeToAvailable(currentNanoTime: Long, tokens: Int): FiniteDuration = {
      val needed = (if (tokens > capacity) 1 else tokens) - tokensGenerated(currentNanoTime)
      (needed / tokensPerSecond).seconds
    }

    private def tokensGenerated(nanoTimeOfSend: Long): Int =
      (TimeUnit.NANOSECONDS.toMillis(nanoTimeOfSend - nanoTimeOfLastSend) * tokensPerSecond / 1000.0).toInt
  }

  @SerialVersionUID(1L)
  case object Unthrottled extends ThrottleMode {
    override def tryConsumeTokens(nanoTimeOfSend: Long, tokens: Int): (ThrottleMode, Boolean) = (this, true)
    override def timeToAvailable(currentNanoTime: Long, tokens: Int): FiniteDuration = Duration.Zero

    /**
     * Java API: get the singleton instance
     */
    def getInstance = this

  }

  @SerialVersionUID(1L)
  case object Blackhole extends ThrottleMode {
    override def tryConsumeTokens(nanoTimeOfSend: Long, tokens: Int): (ThrottleMode, Boolean) = (this, false)
    override def timeToAvailable(currentNanoTime: Long, tokens: Int): FiniteDuration = Duration.Zero

    /**
     * Java API: get the singleton instance
     */
    def getInstance = this
  }

  /**
   * Management Command to force disassociation of an address.
   */
  @SerialVersionUID(1L)
  final case class ForceDisassociate(address: Address)

  /**
   * Management Command to force disassociation of an address with an explicit error.
   */
  @SerialVersionUID(1L)
  @nowarn("msg=deprecated")
  final case class ForceDisassociateExplicitly(address: Address, reason: Any) // FIXME Any was DisassociateInfo

  @SerialVersionUID(1L)
  case object ForceDisassociateAck {

    /**
     * Java API: get the singleton instance
     */
    def getInstance = this
  }

  /**
   * Java API: get the Direction.Send instance
   */
  def sendDirection(): Direction = Direction.Send

  /**
   * Java API: get the Direction.Receive instance
   */
  def receiveDirection(): Direction = Direction.Receive

  /**
   * Java API: get the Direction.Both instance
   */
  def bothDirection(): Direction = Direction.Both

  /**
   * Java API: get the ThrottleMode.Blackhole instance
   */
  def blackholeThrottleMode(): ThrottleMode = Blackhole

  /**
   * Java API: get the ThrottleMode.Unthrottled instance
   */
  def unthrottledThrottleMode(): ThrottleMode = Unthrottled
}
