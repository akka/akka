/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.driver

import akka.NotUsed
import akka.stream.impl.FixedSizeBuffer
import akka.stream.scaladsl.BidiFlow
import akka.stream.{ Attributes, BidiShape, Inlet, Outlet }
import akka.stream.stage._
import akka.util.ByteString

import scala.concurrent.duration._

/**
 * INTERNAL API
 */
private[remote] object FlowAndLossControl {
  val WindowSize = 16
  val BufferMask = WindowSize - 1
  val AckWaterMark = WindowSize >> 1
  val DATA: Byte = 0
  val ACK: Byte = 1

  val AckTimer = "AckTimer"
  val AckPeriod = 10.millis

  def apply(frameBuffer: FrameBuffer): BidiFlow[ByteString, Frame, Frame, ByteString, NotUsed] = {
    BidiFlow.fromGraph(new FlowAndLossControlStage(frameBuffer))
  }
}

/**
 * INTERNAL API
 */
private[remote] final class FlowAndLossControlStage(sndFramePool: FrameBuffer) extends GraphStage[BidiShape[ByteString, Frame, Frame, ByteString]] {
  import FlowAndLossControl._

  val fromApp: Inlet[ByteString] = Inlet("FlowControl.fromApp")
  val toApp: Outlet[ByteString] = Outlet("FlowControl.toApp")
  val fromNet: Inlet[Frame] = Inlet("FlowControl.fromNet")
  val toNet: Outlet[Frame] = Outlet("FlowControl.toNet")
  override val shape: BidiShape[ByteString, Frame, Frame, ByteString] = BidiShape(fromApp, toNet, fromNet, toApp)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    private[this] var nextUnusedOutboundSeq = 0L // TODO: Randomize
    private[this] var highestAckedOutboundSeq = -1L
    private[this] var nextOutboundSeqToSend = 0L
    private[this] var accumulateAckRoundsLeft = AckWaterMark

    private[this] val outboundBuf = Array.ofDim[Frame](WindowSize)
    private[this] val pendingHoles = FixedSizeBuffer[Long](WindowSize) // FIXME would be nice to specialize to Long!

    private[this] var nextContiguousInboundSeq = 0L
    private[this] var highestSeenInboundSeq = 0L
    private[this] var highestDeliveredInboundSeq = -1L
    private[this] val inboundBuf = Array.ofDim[ByteString](WindowSize)

    private[this] var pendingAck: Frame = sndFramePool.acquire()

    override def preStart(): Unit = {
      pull(fromNet)
      pull(fromApp)
    }

    private def slot(seq: Long): Int = (seq & BufferMask).toInt

    private def sendBufferDepleted: Boolean = (nextUnusedOutboundSeq - highestAckedOutboundSeq) == WindowSize + 1
    private def sendWindowAvailable: Boolean = nextOutboundSeqToSend < nextUnusedOutboundSeq

    private def deliverNext(): Unit = {
      val nextSend = outboundBuf(slot(nextOutboundSeqToSend))
      if (nextSend ne null) {
        push(toNet, nextSend)
        nextOutboundSeqToSend += 1
      }
    }

    private def trySendAck(): Boolean = {
      if (accumulateAckRoundsLeft <= 0) {
        calculateAckNack()
        pendingAck.driverReleases = true
        push(toNet, pendingAck)
        pendingAck = sndFramePool.acquire()
        accumulateAckRoundsLeft = AckWaterMark
        true
      } else false
    }

    override protected def onTimer(timerKey: Any): Unit = {
      accumulateAckRoundsLeft = 0
      trySendAck()
    }

    private[this] val outboundHandler = new InHandler with OutHandler {

      override def onPush(): Unit = {
        val frame = sndFramePool.acquire()
        val payload = grab(fromApp)
        val seq = nextUnusedOutboundSeq
        nextUnusedOutboundSeq += 1

        // Add frame
        // FIXME: Needs alignment, no need to read from non-aligned addresses
        frame.buffer.put(DATA)
        frame.buffer.putLong(seq)
        // FIXME: Explicit length information, while not necessary for the usual use-case, can be very useful
        // for crypto where emitting fixed size frames is preferred!

        // Add payload
        frame.writeByteString(payload)
        outboundBuf(slot(seq)) = frame

        if (isAvailable(toNet)) {
          push(toNet, frame)
          nextOutboundSeqToSend = seq + 1
        }

        if (!sendBufferDepleted) pull(fromApp)
      }

      override def onPull(): Unit = {
        if (!trySendAck()) {
          if (!pendingHoles.isEmpty) {
            // Resend nacked Frames first before sending new ones
            val resendSeq = pendingHoles.dequeue()
            push(toNet, outboundBuf(slot(resendSeq)))
          } else if (sendWindowAvailable) {
            deliverNext()
          }
        }

      }

      override def onUpstreamFinish(): Unit = ()
    }

    private[this] val inboundHandler = new InHandler with OutHandler {
      override def onPush(): Unit = {
        val frame = grab(fromNet)
        frame.buffer.flip()
        val tag = frame.buffer.get()
        val seq = frame.buffer.getLong()
        tag match {
          case DATA ⇒ processData(seq, frame.toByteString)
          case ACK  ⇒ processAck(seq, frame)
        }
        frame.release()
      }

      override def onPull(): Unit = {
        val toDeliver = highestDeliveredInboundSeq + 1
        val next = inboundBuf(slot(toDeliver))
        if (next ne null) {
          accumulateAckRoundsLeft -= 1
          if (isAvailable(toNet)) trySendAck()
          inboundBuf(slot(toDeliver)) = null
          push(toApp, next)
          highestDeliveredInboundSeq = toDeliver
        } else {
          // Reader is blocked, schedule ACKs
          schedulePeriodically(AckTimer, AckPeriod)
        }
      }

      override def onUpstreamFinish(): Unit = ()
    }

    private def processAck(seq: Long, frame: Frame): Unit = {
      val previousHighestAckedSeq = highestAckedOutboundSeq
      highestAckedOutboundSeq = seq
      var nacks = frame.buffer.getInt() // Get number of nacks
      while (nacks > 0) {
        val nack = frame.buffer.getLong
        pendingHoles.enqueue(nack)
        highestAckedOutboundSeq = math.min(highestAckedOutboundSeq, nack - 1)
        nacks -= 1
      }

      if (isAvailable(toNet) && pendingHoles.nonEmpty) {
        val resendSeq = pendingHoles.dequeue()
        push(toNet, outboundBuf(slot(resendSeq)))
      }

      // Count the number of Frames that have been gaplessly delivered
      var toFlush = previousHighestAckedSeq + 1
      while (toFlush <= highestAckedOutboundSeq) {
        outboundBuf(slot(toFlush)).release()
        outboundBuf(slot(toFlush)) = null
        toFlush += 1
      }

      // Unblock application if space was freed
      if (!sendBufferDepleted && !hasBeenPulled(fromApp) && !isClosed(fromApp)) pull(fromApp)
      if (sendWindowAvailable && isAvailable(fromNet)) deliverNext()
      pull(fromNet)
    }

    private def processData(seq: Long, payload: ByteString): Unit = {

      if (seq >= nextContiguousInboundSeq && seq <= highestDeliveredInboundSeq + WindowSize) {
        highestSeenInboundSeq = math.max(seq, highestSeenInboundSeq)
        inboundBuf(slot(seq)) = payload
        if (seq == nextContiguousInboundSeq) {
          val endOfWindow = highestSeenInboundSeq + 1
          do nextContiguousInboundSeq += 1
          while ((inboundBuf(slot(nextContiguousInboundSeq)) ne null) &&
            nextContiguousInboundSeq < endOfWindow)
        }
      }

      if (highestDeliveredInboundSeq == seq - 1 && isAvailable(toApp)) {
        inboundBuf(slot(seq)) = null
        accumulateAckRoundsLeft -= 1
        if (isAvailable(toNet)) trySendAck()
        highestDeliveredInboundSeq = seq
        push(toApp, payload)
        // Unblocked app, ACK can be descheduled
        cancelTimer(AckTimer)
      }

      pull(fromNet)
    }

    private def calculateAckNack(): Unit = {
      pendingAck.buffer.put(ACK)
      pendingAck.buffer.putLong(highestDeliveredInboundSeq)
      pendingAck.buffer.putInt(0) // Placeholder for ACK count
      var scan = nextContiguousInboundSeq
      var nacks = 0
      while (scan < highestSeenInboundSeq) {
        if (inboundBuf(slot(scan)) eq null) {
          nacks += 1
          pendingAck.buffer.putLong(scan)
        }
        scan += 1
      }
      pendingAck.buffer.putInt(9, nacks)
    }

    setHandlers(fromApp, toNet, outboundHandler)
    setHandlers(fromNet, toApp, inboundHandler)

  }
}
