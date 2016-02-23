/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import scala.collection.immutable._
import akka.AkkaException

object SeqNo {

  implicit val ord: Ordering[SeqNo] = new Ordering[SeqNo] {
    override def compare(x: SeqNo, y: SeqNo): Int = {
      val sgn = if (x.rawValue < y.rawValue) -1 else if (x.rawValue > y.rawValue) 1 else 0
      if (((x.rawValue - y.rawValue) * sgn) < 0L) -sgn else sgn
    }
  }

}

/**
 * Implements a 64 bit sequence number with proper wrap-around ordering.
 */
final case class SeqNo(rawValue: Long) extends Ordered[SeqNo] {

  /**
   * Checks if this sequence number is an immediate successor of the provided one.
   *
   * @param that The second sequence number that has to be exactly one less
   * @return true if this sequence number is the successor of the provided one
   */
  def isSuccessor(that: SeqNo): Boolean = (this.rawValue - that.rawValue) == 1

  /**
   * Increments the sequence number. Wraps-around if 64 bit limit is reached.
   * @return the incremented sequence number
   */
  def inc: SeqNo = new SeqNo(this.rawValue + 1L)

  override def compare(that: SeqNo) = SeqNo.ord.compare(this, that)

  override def toString = String.valueOf(rawValue)
}

object HasSequenceNumber {
  implicit def seqOrdering[T <: HasSequenceNumber]: Ordering[T] = new Ordering[T] {
    def compare(x: T, y: T) = x.seq.compare(y.seq)
  }
}

/**
 * Messages that are to be buffered in [[akka.remote.AckedSendBuffer]] or [[akka.remote.AckedReceiveBuffer]] has
 * to implement this interface to provide the sequence needed by the buffers.
 */
trait HasSequenceNumber {

  /**
   * Sequence number of the message
   */
  def seq: SeqNo
}

/**
 * Class representing an acknowledgement with selective negative acknowledgements.
 *
 * @param cumulativeAck Represents the highest sequence number received.
 * @param nacks Set of sequence numbers between the last delivered one and cumulativeAck that has been not yet received.
 */
final case class Ack(cumulativeAck: SeqNo, nacks: Set[SeqNo] = Set.empty) {
  override def toString = s"ACK[$cumulativeAck, ${nacks.mkString("{", ", ", "}")}]"
}

class ResendBufferCapacityReachedException(c: Int)
  extends AkkaException(s"Resend buffer capacity of [$c] has been reached.")

class ResendUnfulfillableException
  extends AkkaException("Unable to fulfill resend request since negatively acknowledged payload is no longer in buffer. " +
    "The resend states between two systems are compromised and cannot be recovered.")

/**
 * Implements an immutable resend buffer that buffers messages until they have been acknowledged. Properly removes messages
 * when an ack is received. This buffer works together with [[akka.remote.AckedReceiveBuffer]] on the receiving end.
 *
 * @param capacity Maximum number of messages the buffer is willing to accept. If reached [[akka.remote.ResendBufferCapacityReachedException]]
 *                 is thrown.
 * @param nonAcked Sequence of messages that has not yet been acknowledged.
 * @param nacked   Sequence of messages that has been explicitly negative acknowledged.
 * @param maxSeq The maximum sequence number that has been stored in this buffer. Messages having lower sequence number
 *               will be not stored but rejected with [[java.lang.IllegalArgumentException]]
 */
final case class AckedSendBuffer[T <: HasSequenceNumber](
  capacity: Int,
  nonAcked: IndexedSeq[T] = Vector.empty[T],
  nacked: IndexedSeq[T] = Vector.empty[T],
  maxSeq: SeqNo = SeqNo(-1)) {

  /**
   * Processes an incoming acknowledgement and returns a new buffer with only unacknowledged elements remaining.
   * @param ack The received acknowledgement
   * @return An updated buffer containing the remaining unacknowledged messages
   */
  def acknowledge(ack: Ack): AckedSendBuffer[T] = {
    if (ack.cumulativeAck > maxSeq)
      throw new IllegalArgumentException(s"Highest SEQ so far was $maxSeq but cumulative ACK is ${ack.cumulativeAck}")
    val newNacked =
      if (ack.nacks.isEmpty) Vector.empty
      else (nacked ++ nonAcked) filter { m ⇒ ack.nacks(m.seq) }
    if (newNacked.size < ack.nacks.size) throw new ResendUnfulfillableException
    else this.copy(
      nonAcked = nonAcked.filter { m ⇒ m.seq > ack.cumulativeAck },
      nacked = newNacked)
  }

  /**
   * Puts a new message in the buffer. Throws [[java.lang.IllegalArgumentException]] if an out-of-sequence message
   * is attempted to be stored.
   * @param msg The message to be stored for possible future retransmission.
   * @return The updated buffer
   */
  def buffer(msg: T): AckedSendBuffer[T] = {
    if (msg.seq <= maxSeq) throw new IllegalArgumentException(s"Sequence number must be monotonic. Received [${msg.seq}] " +
      s"which is smaller than [$maxSeq]")

    if (nonAcked.size == capacity) throw new ResendBufferCapacityReachedException(capacity)

    this.copy(nonAcked = this.nonAcked :+ msg, maxSeq = msg.seq)
  }

  override def toString = s"[$maxSeq ${nonAcked.map(_.seq).mkString("{", ", ", "}")}]"
}

/**
 * Implements an immutable receive buffer that buffers incoming messages until they can be safely delivered. This
 * buffer works together with a [[akka.remote.AckedSendBuffer]] on the sender() side.
 *
 * @param lastDelivered Sequence number of the last message that has been delivered.
 * @param cumulativeAck The highest sequence number received so far.
 * @param buf Buffer of messages that are waiting for delivery
 */
final case class AckedReceiveBuffer[T <: HasSequenceNumber](
  lastDelivered: SeqNo = SeqNo(-1),
  cumulativeAck: SeqNo = SeqNo(-1),
  buf: SortedSet[T] = TreeSet.empty[T])(implicit val seqOrdering: Ordering[T]) {

  import SeqNo.ord.max

  /**
   * Puts a sequenced message in the receive buffer returning a new buffer.
   * @param arrivedMsg message to be put into the buffer.
   * @return The updated buffer containing the message.
   */
  def receive(arrivedMsg: T): AckedReceiveBuffer[T] = {
    this.copy(
      cumulativeAck = max(arrivedMsg.seq, cumulativeAck),
      buf = if (arrivedMsg.seq > lastDelivered && !buf.contains(arrivedMsg)) buf + arrivedMsg else buf)
  }

  /**
   * Extract all messages that could be safely delivered, an updated ack to be sent to the sender(), and an updated
   * buffer that has the messages removed that can be delivered.
   * @return Triplet of the updated buffer, messages that can be delivered and the updated acknowledgement.
   */
  def extractDeliverable: (AckedReceiveBuffer[T], Seq[T], Ack) = {
    var deliver = Vector.empty[T]
    var ack = Ack(cumulativeAck = cumulativeAck)
    var updatedLastDelivered = lastDelivered
    var prev = lastDelivered

    for (bufferedMsg ← buf) {
      if (bufferedMsg.seq.isSuccessor(updatedLastDelivered)) {
        deliver :+= bufferedMsg
        updatedLastDelivered = updatedLastDelivered.inc
      } else if (!bufferedMsg.seq.isSuccessor(prev)) {
        var diff = bufferedMsg.seq.rawValue - prev.rawValue - 1
        var nacks = Set.empty[SeqNo]

        // Collect all missing sequence numbers (gaps)
        while (diff > 0) {
          nacks += SeqNo(prev.rawValue + diff)
          diff -= 1
        }
        ack = ack.copy(nacks = ack.nacks ++ nacks)
      }
      prev = bufferedMsg.seq
    }

    val newBuf = if (deliver.isEmpty) buf else buf.filterNot(deliver.contains)
    (this.copy(buf = newBuf, lastDelivered = updatedLastDelivered), deliver, ack)
  }

  /**
   * Merges two receive buffers. Merging preserves sequencing of messages, and drops all messages that has been
   * safely acknowledged by any of the participating buffers. Also updates the expected sequence numbers.
   * @param that The receive buffer to merge with
   * @return The merged receive buffer.
   */
  def mergeFrom(that: AckedReceiveBuffer[T]): AckedReceiveBuffer[T] = {
    val mergedLastDelivered = max(this.lastDelivered, that.lastDelivered)
    this.copy(
      lastDelivered = mergedLastDelivered,
      cumulativeAck = max(this.cumulativeAck, that.cumulativeAck),
      buf = (this.buf union that.buf).filter { _.seq > mergedLastDelivered })
  }

  override def toString = buf.map { _.seq }.mkString("[", ", ", "]")
}
