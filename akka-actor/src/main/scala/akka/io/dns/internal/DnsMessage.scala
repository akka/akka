/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import akka.annotation.InternalApi
import akka.io.dns.ResourceRecord
import akka.util.{ ByteString, ByteStringBuilder }

import scala.collection.immutable.Seq

/**
 * INTERNAL API
 */
@InternalApi
private[internal] object OpCode extends Enumeration {
  val QUERY = Value(0)
  val IQUERY = Value(1)
  val STATUS = Value(2)
}

/**
 * INTERNAL API
 */
@InternalApi
private[internal] object ResponseCode extends Enumeration {
  val SUCCESS = Value(0)
  val FORMAT_ERROR = Value(1)
  val SERVER_FAILURE = Value(2)
  val NAME_ERROR = Value(3)
  val NOT_IMPLEMENTED = Value(4)
  val REFUSED = Value(5)
}

/**
 * INTERNAL API
 */
@InternalApi
private[internal] case class MessageFlags(flags: Short) extends AnyVal {
  def isQuery: Boolean = (flags & 0x8000) == 0

  def isAnswer = !isQuery

  def opCode: OpCode.Value = OpCode((flags & 0x7800) >> 11)

  def isAuthoritativeAnswer: Boolean = (flags & (1 << 10)) != 0

  def isTruncated: Boolean = (flags & (1 << 9)) != 0

  def isRecursionDesired: Boolean = (flags & (1 << 8)) != 0

  def isRecursionAvailable: Boolean = (flags & (1 << 7)) != 0

  def responseCode: ResponseCode.Value = {
    ResponseCode(flags & 0x0f)
  }

  override def toString: String = {
    var ret = List[String]()
    ret +:= s"$responseCode"
    if (isRecursionAvailable) ret +:= "RA"
    if (isRecursionDesired) ret +:= "RD"
    if (isTruncated) ret +:= "TR"
    if (isAuthoritativeAnswer) ret +:= "AA"
    ret +:= s"$opCode"
    if (isAnswer) ret +:= "AN"
    ret.mkString("<", ",", ">")
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[internal] object MessageFlags {
  def apply(answer: Boolean = false, opCode: OpCode.Value = OpCode.QUERY, authoritativeAnswer: Boolean = false,
            truncated: Boolean = false, recursionDesired: Boolean = true, recursionAvailable: Boolean = false,
            responseCode: ResponseCode.Value = ResponseCode.SUCCESS): MessageFlags = {
    new MessageFlags((
      (if (answer) 0x8000 else 0) |
      (opCode.id << 11) |
      (if (authoritativeAnswer) 1 << 10 else 0) |
      (if (truncated) 1 << 9 else 0) |
      (if (recursionDesired) 1 << 8 else 0) |
      (if (recursionAvailable) 1 << 7 else 0) |
      responseCode.id).toShort)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[internal] case class Message(
  id:             Short,
  flags:          MessageFlags,
  questions:      Seq[Question]       = Seq.empty,
  answerRecs:     Seq[ResourceRecord] = Seq.empty,
  authorityRecs:  Seq[ResourceRecord] = Seq.empty,
  additionalRecs: Seq[ResourceRecord] = Seq.empty) {
  def write(): ByteString = {
    val ret = ByteString.newBuilder
    write(ret)
    ret.result()
  }

  def write(ret: ByteStringBuilder): Unit = {
    ret.putShort(id)
      .putShort(flags.flags)
      .putShort(questions.size)
      .putShort(answerRecs.size)
      .putShort(authorityRecs.size)
      .putShort(additionalRecs.size)

    questions.foreach(_.write(ret))
    answerRecs.foreach(_.write(ret))
    authorityRecs.foreach(_.write(ret))
    additionalRecs.foreach(_.write(ret))
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[internal] object Message {
  def parse(msg: ByteString): Message = {
    val it = msg.iterator
    val id = it.getShort
    val flags = it.getShort
    val qdCount = it.getShort
    val anCount = it.getShort
    val nsCount = it.getShort
    val arCount = it.getShort

    val qs = (0 until qdCount) map { i ⇒ Question.parse(it, msg) }
    val ans = (0 until anCount) map { i ⇒ ResourceRecord.parse(it, msg) }
    val nss = (0 until nsCount) map { i ⇒ ResourceRecord.parse(it, msg) }
    val ars = (0 until arCount) map { i ⇒ ResourceRecord.parse(it, msg) }

    new Message(id, new MessageFlags(flags), qs, ans, nss, ars)
  }
}
