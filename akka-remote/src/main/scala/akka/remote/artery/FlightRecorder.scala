/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.nio.{ ByteBuffer, ByteOrder }
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

import akka.util.ByteString
import org.agrona.BitUtil
import org.agrona.concurrent.MappedResizeableBuffer

import scala.annotation.tailrec

/**
 * INTERNAL API
 */
private[remote] trait EventSink {
  def alert(code: Int, metadata: Array[Byte]): Unit
  def loFreq(code: Int, metadata: Array[Byte]): Unit
  def hiFreq(code: Long, param: Long): Unit
}

/**
 * INTERNAL API
 *
 * Update clock at various resolutions and aquire the resulting timestamp.
 */
private[remote] trait EventClock {

  def updateWallClock(): Unit
  def updateHighSpeedClock(): Unit

  def getWallClockPart: Long
  def getHighSpeedPart: Long

}

/**
 * INTERNAL API
 *
 * This class is not thread-safe
 */
private[remote] class EventClockImpl extends EventClock {

  private[this] var wallClock: Long = System.currentTimeMillis()
  private[this] var highSpeedClock: Long = System.nanoTime()

  override def updateWallClock(): Unit = {
    wallClock = System.currentTimeMillis()
    highSpeedClock = System.nanoTime()
  }

  override def updateHighSpeedClock(): Unit = {
    // TODO: Update wall clock once in a while
    highSpeedClock = System.nanoTime()
  }

  override def getWallClockPart: Long = wallClock
  override def getHighSpeedPart: Long = highSpeedClock
}

/**
 * INTERNAL API
 */
private[remote] object SnapshottableRollingEventLog {
  val HeadPointerOffset = 0L
  val EntriesOffset = 8L
  val LogOffset = 0L

  val Committed = 0
  val Dirty = 1
  val CommitEntrySize = 4
}

/**
 * INTERNAL API
 */
private[remote] class SnapshottableRollingEventLog(
  fileChannel: FileChannel,
  offset: Long,
  entryCount: Long,
  logBufferSize: Long,
  recordSize: Int) extends AtomicBoolean {
  import SnapshottableRollingEventLog._

  // FIXME: check if power of two
  private[this] val LogMask: Long = entryCount - 1L

  private[this] val buffers: Array[MappedResizeableBuffer] = Array.tabulate(FlightRecorder.SnapshotCount) { snapshotId ⇒
    val buffer = new MappedResizeableBuffer(fileChannel, offset + snapshotId * logBufferSize, logBufferSize)
    buffer.setMemory(0, logBufferSize.toInt, 0.toByte)
    buffer
  }

  /*
   * The logic here MUST be kept in sync with its simulated version in RollingEventLogSimulationSpec as it
   * is currently the best place to do in-depth stress-testing of this logic. Unfortunately currently there is no
   * sane way to use the same code here and in the test, too.
   */
  def write(log: Int, recordBuffer: ByteBuffer): Unit = {
    val logBuffer = buffers(log)

    @tailrec def writeRecord(): Unit = {
      // Advance the head
      val slotOffset = EntriesOffset + ((logBuffer.getAndAddLong(HeadPointerOffset, 1L) & LogMask) * recordSize)
      val payloadOffset = slotOffset + CommitEntrySize
      // Signal that we write to the record. This is to prevent concurrent writes to the same slot
      // if the head *wraps over* and points again to this location. Without this we would end up with partial or corrupted
      // writes to the slot.
      if (logBuffer.compareAndSetInt(slotOffset, Committed, Dirty)) {
        logBuffer.putBytes(payloadOffset, recordBuffer, recordSize)
        //println(logBuffer.getLong(recordOffset + 4))

        // Now this is free to be overwritten
        logBuffer.putIntVolatile(slotOffset, Committed)
      } else writeRecord() // Try to claim a new slot
    }

    writeRecord()
  }

  def close(): Unit = buffers.foreach(_.close())
}

/**
 * INTERNAL API
 */
private[remote] object FlightRecorder {
  val LogHeaderSize = 8
  val SnapshotCount = 4
  val SnapshotMask = SnapshotCount - 1

  // TODO: Dummy values right now, format is under construction
  val AlertRecordSize = 128
  val LoFreqRecordSize = 128
  val HiFreqBatchSize = 63
  val HiFreqRecordSize = 16 * (HiFreqBatchSize + 1) // (batched events + header)

  val AlertWindow = 256
  val LoFreqWindow = 256
  val HiFreqWindow = 256 // This is counted in batches !

  val Alignment = 64 * 1024 // Windows is picky about mapped section alignments

  val AlertLogSize = BitUtil.align(LogHeaderSize + (AlertWindow * AlertRecordSize), Alignment)
  val LoFreqLogSize = BitUtil.align(LogHeaderSize + (LoFreqWindow * LoFreqRecordSize), Alignment)
  val HiFreqLogSize = BitUtil.align(LogHeaderSize + (HiFreqWindow * HiFreqRecordSize), Alignment)

  val AlertSectionSize = AlertLogSize * SnapshotCount
  val LoFreqSectionSize = HiFreqLogSize * SnapshotCount
  val HiFreqSectionSize = LoFreqLogSize * SnapshotCount
}

/**
 * INTERNAL API
 */
private[akka] class FlightRecorder(fileChannel: FileChannel) extends AtomicBoolean {
  import FlightRecorder._

  // FIXME: check if power of two
  private[this] val SnapshotMask = SnapshotCount - 1
  private[this] val alertLogs =
    new SnapshottableRollingEventLog(
      fileChannel = fileChannel,
      offset = AlertSectionSize + HiFreqSectionSize,
      entryCount = AlertWindow,
      logBufferSize = AlertLogSize,
      recordSize = AlertRecordSize)
  private[this] val loFreqLogs =
    new SnapshottableRollingEventLog(
      fileChannel = fileChannel,
      offset = AlertSectionSize,
      entryCount = LoFreqWindow,
      logBufferSize = LoFreqLogSize,
      recordSize = LoFreqRecordSize)
  private[this] val hiFreqLogs =
    new SnapshottableRollingEventLog(
      fileChannel = fileChannel,
      offset = 0,
      entryCount = HiFreqWindow,
      logBufferSize = HiFreqLogSize,
      recordSize = HiFreqRecordSize)
  // No need for volatile, guarded by atomic CAS and set
  private var currentLog = 0

  def takeSnapshot(): Unit = {
    // Coalesce concurrent snapshot requests into one, i.e. ignore the "late-comers".
    // In other words, this is a critical section in which participants either enter, or just
    // simply skip ("Hm, seems someone else already does it. ¯\_(ツ)_/¯ ")
    if (!get && compareAndSet(false, true)) {
      // Roll over to the next one
      currentLog = (currentLog + 1) & SnapshotMask
      set(false)
      // At this point it is NOT GUARANTEED that all writers have finished writing to the currently snapshotted
      // buffer!
    }
  }

  def close(): Unit = {
    alertLogs.close()
    hiFreqLogs.close()
    loFreqLogs.close()
  }

  def createEventSink(): EventSink = new EventSink {
    private[this] val clock = new EventClockImpl
    private[this] val alertRecordBuffer = ByteBuffer.allocate(AlertRecordSize).order(ByteOrder.LITTLE_ENDIAN)
    private[this] val loFreqRecordBuffer = ByteBuffer.allocate(LoFreqRecordSize).order(ByteOrder.LITTLE_ENDIAN)
    private[this] val hiFreqBatchBuffer = ByteBuffer.allocate(HiFreqRecordSize).order(ByteOrder.LITTLE_ENDIAN)

    override def alert(code: Int, metadata: Array[Byte]): Unit = {
      clock.updateWallClock()
      prepareRichRecord(alertRecordBuffer, code, metadata)
      alertLogs.write(currentLog, alertRecordBuffer)
      // TODO: Flush HiFreq batch

      // TODO: collect a few more events and *then* take a snapshot
      takeSnapshot()
    }

    override def loFreq(code: Int, metadata: Array[Byte]): Unit = {
      clock.updateHighSpeedClock()
      prepareRichRecord(loFreqRecordBuffer, code, metadata)
      loFreqLogs.write(currentLog, loFreqRecordBuffer)
    }

    private def prepareRichRecord(recordBuffer: ByteBuffer, code: Int, metadata: Array[Byte]): Unit = {
      recordBuffer.clear()
      // FIXME: This is a bit overkill, needs some smarter scheme later, no need to always store the wallclock
      recordBuffer.putLong(clock.getWallClockPart)
      recordBuffer.putLong(clock.getHighSpeedPart)
      recordBuffer.putInt(code)
      recordBuffer.put(metadata.length.toByte)
      recordBuffer.put(metadata)
      // Don't flip here! We always write fixed size records
      recordBuffer.position(0)
    }

    // FIXME: Try to save as many bytes here as possible! We will see crazy throughput here
    override def hiFreq(code: Long, param: Long): Unit = {
      hiFreqBatchBuffer.putLong(code)
      hiFreqBatchBuffer.putLong(param)

      // If batch is full, time to flush
      if (!hiFreqBatchBuffer.hasRemaining) {
        hiFreqBatchBuffer.position(0)
        hiFreqLogs.write(currentLog, hiFreqBatchBuffer)
        hiFreqBatchBuffer.clear()
        // Refresh the nanotime
        clock.updateHighSpeedClock()
        // Header of the batch will contain our most accurate knowledge of the clock, individual entries do not
        // contain any timestamp
        hiFreqBatchBuffer.putLong(clock.getWallClockPart)
        hiFreqBatchBuffer.putLong(clock.getHighSpeedPart)
        // Mow ready to write some more events...
      }
    }

  }
}
