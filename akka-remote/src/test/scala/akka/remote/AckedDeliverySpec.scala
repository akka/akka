/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import java.util.concurrent.ThreadLocalRandom

import scala.annotation.tailrec

import scala.annotation.nowarn

import akka.testkit.AkkaSpec

@nowarn("msg=deprecated")
object AckedDeliverySpec {

  final case class Sequenced(seq: SeqNo, body: String) extends HasSequenceNumber {
    override def toString = s"MSG[${seq.rawValue}]"
  }

}

@nowarn("msg=deprecated")
class AckedDeliverySpec extends AkkaSpec {
  import AckedDeliverySpec._

  def msg(seq: Long) = Sequenced(SeqNo(seq), "msg" + seq)

  "SeqNo" must {

    "implement simple ordering" in {
      val sm1 = SeqNo(-1)
      val s0 = SeqNo(0)
      val s1 = SeqNo(1)
      val s2 = SeqNo(2)
      val s0b = SeqNo(0)

      sm1 < s0 should ===(true)
      sm1 > s0 should ===(false)

      s0 < s1 should ===(true)
      s0 > s1 should ===(false)

      s1 < s2 should ===(true)
      s1 > s2 should ===(false)

      s0b == s0 should ===(true)
    }

    "correctly handle wrapping over" in {
      val s1 = SeqNo(Long.MaxValue - 1)
      val s2 = SeqNo(Long.MaxValue)
      val s3 = SeqNo(Long.MinValue)
      val s4 = SeqNo(Long.MinValue + 1)

      s1 < s2 should ===(true)
      s1 > s2 should ===(false)

      s2 < s3 should ===(true)
      s2 > s3 should ===(false)

      s3 < s4 should ===(true)
      s3 > s4 should ===(false)
    }

    "correctly handle large gaps" in {
      val smin = SeqNo(Long.MinValue)
      val smin2 = SeqNo(Long.MinValue + 1)
      val s0 = SeqNo(0)

      s0 < smin should ===(true)
      s0 > smin should ===(false)

      smin2 < s0 should ===(true)
      smin2 > s0 should ===(false)
    }

  }

  "SendBuffer" must {

    "aggregate unacked messages in order" in {
      val b0 = new AckedSendBuffer[Sequenced](10)
      val msg0 = msg(0)
      val msg1 = msg(1)
      val msg2 = msg(2)

      val b1 = b0.buffer(msg0)
      b1.nonAcked should ===(Vector(msg0))

      val b2 = b1.buffer(msg1)
      b2.nonAcked should ===(Vector(msg0, msg1))

      val b3 = b2.buffer(msg2)
      b3.nonAcked should ===(Vector(msg0, msg1, msg2))

    }

    "refuse buffering new messages if capacity reached" in {
      val buffer = new AckedSendBuffer[Sequenced](4).buffer(msg(0)).buffer(msg(1)).buffer(msg(2)).buffer(msg(3))

      intercept[ResendBufferCapacityReachedException] {
        buffer.buffer(msg(4))
      }
    }

    "remove messages from buffer when cumulative ack received" in {
      val b0 = new AckedSendBuffer[Sequenced](10)
      val msg0 = msg(0)
      val msg1 = msg(1)
      val msg2 = msg(2)
      val msg3 = msg(3)
      val msg4 = msg(4)

      val b1 = b0.buffer(msg0)
      b1.nonAcked should ===(Vector(msg0))

      val b2 = b1.buffer(msg1)
      b2.nonAcked should ===(Vector(msg0, msg1))

      val b3 = b2.buffer(msg2)
      b3.nonAcked should ===(Vector(msg0, msg1, msg2))

      val b4 = b3.acknowledge(Ack(SeqNo(1)))
      b4.nonAcked should ===(Vector(msg2))

      val b5 = b4.buffer(msg3)
      b5.nonAcked should ===(Vector(msg2, msg3))

      val b6 = b5.buffer(msg4)
      b6.nonAcked should ===(Vector(msg2, msg3, msg4))

      val b7 = b6.acknowledge(Ack(SeqNo(1)))
      b7.nonAcked should ===(Vector(msg2, msg3, msg4))

      val b8 = b7.acknowledge(Ack(SeqNo(2)))
      b8.nonAcked should ===(Vector(msg3, msg4))

      val b9 = b8.acknowledge(Ack(SeqNo(4)))
      b9.nonAcked should ===(Vector.empty[Sequenced])

    }

    "keep NACKed messages in buffer if selective nacks are received" in {
      val b0 = new AckedSendBuffer[Sequenced](10)
      val msg0 = msg(0)
      val msg1 = msg(1)
      val msg2 = msg(2)
      val msg3 = msg(3)
      val msg4 = msg(4)

      val b1 = b0.buffer(msg0)
      b1.nonAcked should ===(Vector(msg0))

      val b2 = b1.buffer(msg1)
      b2.nonAcked should ===(Vector(msg0, msg1))

      val b3 = b2.buffer(msg2)
      b3.nonAcked should ===(Vector(msg0, msg1, msg2))

      val b4 = b3.acknowledge(Ack(SeqNo(1), nacks = Set(SeqNo(0))))
      b4.nonAcked should ===(Vector(msg2))
      b4.nacked should ===(Vector(msg0))

      val b5 = b4.buffer(msg3).buffer(msg4)
      b5.nonAcked should ===(Vector(msg2, msg3, msg4))
      b5.nacked should ===(Vector(msg0))

      val b6 = b5.acknowledge(Ack(SeqNo(4), nacks = Set(SeqNo(2), SeqNo(3))))
      b6.nonAcked should ===(Vector())
      b6.nacked should ===(Vector(msg2, msg3))

      val b7 = b6.acknowledge(Ack(SeqNo(4)))
      b7.nonAcked should ===(Vector.empty[Sequenced])
      b7.nacked should ===(Vector.empty[Sequenced])
    }

    "throw exception if non-buffered sequence number is NACKed" in {
      val b0 = new AckedSendBuffer[Sequenced](10)
      val msg1 = msg(1)
      val msg2 = msg(2)

      val b1 = b0.buffer(msg1).buffer(msg2)
      intercept[ResendUnfulfillableException] {
        b1.acknowledge(Ack(SeqNo(2), nacks = Set(SeqNo(0))))
      }
    }

  }

  "ReceiveBuffer" must {

    "enqueue message in buffer if needed, return the list of deliverable messages and acks" in {
      val b0 = new AckedReceiveBuffer[Sequenced]
      val msg0 = msg(0)
      val msg1 = msg(1)
      val msg2 = msg(2)
      val msg3 = msg(3)
      val msg4 = msg(4)
      val msg5 = msg(5)

      val (b1, deliver1, ack1) = b0.receive(msg1).extractDeliverable
      deliver1 should ===(Vector.empty[Sequenced])
      ack1 should ===(Ack(SeqNo(1), nacks = Set(SeqNo(0))))

      val (b2, deliver2, ack2) = b1.receive(msg0).extractDeliverable
      deliver2 should ===(Vector(msg0, msg1))
      ack2 should ===(Ack(SeqNo(1)))

      val (b3, deliver3, ack3) = b2.receive(msg4).extractDeliverable
      deliver3 should ===(Vector.empty[Sequenced])
      ack3 should ===(Ack(SeqNo(4), nacks = Set(SeqNo(2), SeqNo(3))))

      val (b4, deliver4, ack4) = b3.receive(msg2).extractDeliverable
      deliver4 should ===(Vector(msg2))
      ack4 should ===(Ack(SeqNo(4), nacks = Set(SeqNo(3))))

      val (b5, deliver5, ack5) = b4.receive(msg5).extractDeliverable
      deliver5 should ===(Vector.empty[Sequenced])
      ack5 should ===(Ack(SeqNo(5), nacks = Set(SeqNo(3))))

      val (_, deliver6, ack6) = b5.receive(msg3).extractDeliverable
      deliver6 should ===(Vector(msg3, msg4, msg5))
      ack6 should ===(Ack(SeqNo(5)))

    }

    "handle duplicate arrivals correctly" in {
      val buf = new AckedReceiveBuffer[Sequenced]
      val msg0 = msg(0)
      val msg1 = msg(1)
      val msg2 = msg(2)

      val (buf2, _, _) = buf.receive(msg0).receive(msg1).receive(msg2).extractDeliverable

      val buf3 = buf2.receive(msg0).receive(msg1).receive(msg2)

      val (_, deliver, ack) = buf3.extractDeliverable

      deliver should ===(Vector.empty[Sequenced])
      ack should ===(Ack(SeqNo(2)))
    }

    "be able to correctly merge with another receive buffer" in {
      val buf1 = new AckedReceiveBuffer[Sequenced]
      val buf2 = new AckedReceiveBuffer[Sequenced]
      val msg0 = msg(0)
      val msg1a = msg(1)
      val msg1b = msg(1)
      val msg2 = msg(2)
      val msg3 = msg(3)

      val buf = buf1.receive(msg1a).receive(msg2).mergeFrom(buf2.receive(msg1b).receive(msg3))

      val (_, deliver, ack) = buf.receive(msg0).extractDeliverable
      deliver should ===(Vector(msg0, msg1a, msg2, msg3))
      ack should ===(Ack(SeqNo(3)))
    }
  }

  "SendBuffer and ReceiveBuffer" must {

    def happened(p: Double) = ThreadLocalRandom.current().nextDouble() < p

    @tailrec def geom(p: Double, limit: Int, acc: Int = 0): Int =
      if (acc == limit) acc
      else if (happened(p)) acc
      else geom(p, limit, acc + 1)

    "correctly cooperate with each other" in {
      val MsgCount = 1000
      val DeliveryProbability = 0.5
      val referenceList: Seq[Sequenced] = (0 until MsgCount).toSeq.map { i =>
        msg(i.toLong)
      }

      var toSend = referenceList
      var received = Seq.empty[Sequenced]
      var sndBuf = new AckedSendBuffer[Sequenced](10)
      var rcvBuf = new AckedReceiveBuffer[Sequenced]
      var log = Vector.empty[String]
      var lastAck: Ack = Ack(SeqNo(-1))

      def dbgLog(message: String): Unit = log :+= message

      def senderSteps(steps: Int, p: Double) = {
        val resends = (sndBuf.nacked ++ sndBuf.nonAcked).take(steps)

        val sends = if (steps - resends.size > 0) {
          val tmp = toSend.take(steps - resends.size)
          toSend = toSend.drop(steps - resends.size)
          tmp
        } else Seq.empty[Sequenced]

        (resends ++ sends).foreach { msg =>
          if (sends.contains(msg)) sndBuf = sndBuf.buffer(msg)
          if (happened(p)) {
            val (updatedRcvBuf, delivers, ack) = rcvBuf.receive(msg).extractDeliverable
            rcvBuf = updatedRcvBuf
            dbgLog(s"$sndBuf -- $msg --> $rcvBuf")
            lastAck = ack
            received ++= delivers
            dbgLog(s"R: ${received.mkString(", ")}")
          } else dbgLog(s"$sndBuf -- $msg --X $rcvBuf")
        }
      }

      def receiverStep(p: Double) = {
        if (happened(p)) {
          sndBuf = sndBuf.acknowledge(lastAck)
          dbgLog(s"$sndBuf <-- $lastAck -- $rcvBuf")
        } else dbgLog(s"$sndBuf X-- $lastAck -- $rcvBuf")
      }

      // Dropping phase
      info(s"Starting unreliable delivery for $MsgCount messages, with delivery probability P = $DeliveryProbability")
      var steps = MsgCount * 2
      while (steps > 0) {
        val s = geom(0.3, limit = 5)
        senderSteps(s, DeliveryProbability)
        receiverStep(DeliveryProbability)
        steps -= s
      }
      info(s"Successfully delivered ${received.size} messages from ${MsgCount}")
      info("Entering reliable phase")

      // Finalizing phase
      for (_ <- 1 to MsgCount) {
        senderSteps(1, 1.0)
        receiverStep(1.0)
      }

      if (received != referenceList) {
        println(log.mkString("\n"))
        println("Received:")
        println(received)
        fail("Not all messages were received")
      }

      info("All messages have been successfully delivered")

    }

  }

}
