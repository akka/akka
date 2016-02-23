/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

/**
 * Contains WebSocket protocol constants
 *
 * INTERNAL API
 */
private[http] object Protocol {
  val FIN_MASK = 0x80
  val RSV1_MASK = 0x40
  val RSV2_MASK = 0x20
  val RSV3_MASK = 0x10

  val FLAGS_MASK = 0xF0
  val OP_MASK = 0x0F

  val MASK_MASK = 0x80
  val LENGTH_MASK = 0x7F

  sealed trait Opcode {
    def code: Byte
    def isControl: Boolean
  }
  object Opcode {
    def forCode(code: Byte): Opcode = code match {
      case 0x0                  ⇒ Continuation
      case 0x1                  ⇒ Text
      case 0x2                  ⇒ Binary

      case 0x8                  ⇒ Close
      case 0x9                  ⇒ Ping
      case 0xA                  ⇒ Pong

      case b if (b & 0xf0) == 0 ⇒ Other(code)
      case _                    ⇒ throw new IllegalArgumentException(f"Opcode must be 4bit long but was 0x$code%02X")
    }

    sealed abstract class AbstractOpcode private[Opcode] (val code: Byte) extends Opcode {
      def isControl: Boolean = (code & 0x8) != 0
    }

    case object Continuation extends AbstractOpcode(0x0)
    case object Text extends AbstractOpcode(0x1)
    case object Binary extends AbstractOpcode(0x2)

    case object Close extends AbstractOpcode(0x8)
    case object Ping extends AbstractOpcode(0x9)
    case object Pong extends AbstractOpcode(0xA)

    case class Other(override val code: Byte) extends AbstractOpcode(code)
  }

  /**
   * Close status codes as defined at http://tools.ietf.org/html/rfc6455#section-7.4.1
   */
  object CloseCodes {
    def isError(code: Int): Boolean = !(code == Regular || code == GoingAway)
    def isValid(code: Int): Boolean =
      ((code >= 1000) && (code <= 1003)) ||
        (code >= 1007) && (code <= 1011) ||
        (code >= 3000) && (code <= 4999)

    val Regular = 1000
    val GoingAway = 1001
    val ProtocolError = 1002
    val Unacceptable = 1003
    // Reserved = 1004
    // NoCodePresent = 1005
    val ConnectionAbort = 1006
    val InconsistentData = 1007
    val PolicyViolated = 1008
    val TooBig = 1009
    val ClientRejectsExtension = 1010
    val UnexpectedCondition = 1011
    val TLSHandshakeFailure = 1015
  }
}

/** INTERNAL API */
private[http] case class ProtocolException(cause: String) extends RuntimeException(cause)