/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file._
import java.nio.{ ByteBuffer, ByteOrder }
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ CountDownLatch, TimeUnit }

import org.agrona.BitUtil
import org.agrona.concurrent.MappedResizeableBuffer

import scala.annotation.tailrec

/**
 * INTERNAL API
 */
private[remote] trait EventSink {
  def alert(code: Int, metadata: Array[Byte]): Unit
  def alert(code: Int, metadata: String): Unit
  def loFreq(code: Int, metadata: Array[Byte]): Unit
  def loFreq(code: Int, metadata: String): Unit
  def hiFreq(code: Long, param: Long): Unit

  def flushHiFreqBatch(): Unit
}

/**
 * INTERNAL API
 */
private[remote] object IgnoreEventSink extends EventSink {
  override def alert(code: Int, metadata: Array[Byte]): Unit = ()
  override def alert(code: Int, metadata: String): Unit = ()
  override def loFreq(code: Int, metadata: Array[Byte]): Unit = ()
  override def loFreq(code: Int, metadata: String): Unit = ()
  override def flushHiFreqBatch(): Unit = ()
  override def hiFreq(code: Long, param: Long): Unit = ()
}

/**
 * INTERNAL API
 */
private[remote] class SynchronizedEventSink(delegate: EventSink) extends EventSink {
  override def alert(code: Int, metadata: Array[Byte]): Unit = synchronized {
    delegate.alert(code, metadata)
  }

  override def alert(code: Int, metadata: String): Unit = {
    alert(code, metadata.getBytes("US-ASCII"))
  }

  override def loFreq(code: Int, metadata: Array[Byte]): Unit = synchronized {
    delegate.loFreq(code, metadata)
  }

  override def loFreq(code: Int, metadata: String): Unit = {
    loFreq(code, metadata.getBytes("US-ASCII"))
  }

  override def flushHiFreqBatch(): Unit = synchronized {
    delegate.flushHiFreqBatch()
  }

  override def hiFreq(code: Long, param: Long): Unit = synchronized {
    delegate.hiFreq(code, param)
  }
}

/**
 * INTERNAL API
 *
 * Update clock at various resolutions and acquire the resulting timestamp.
 */
private[remote] trait EventClock {

  def updateWallClock(): Unit
  def updateHighSpeedClock(): Unit

  def wallClockPart: Long
  def highSpeedPart: Long

}

/**
 * INTERNAL API
 *
 * This class is not thread-safe
 */
private[remote] class EventClockImpl extends EventClock {

  private[this] var wallClock: Long = 0
  private[this] var highSpeedClock: Long = 0
  private[this] var highSpeedClockOffset: Long = 0

  updateWallClock()

  override def updateWallClock(): Unit = {
    wallClock = System.currentTimeMillis()
    highSpeedClockOffset = System.nanoTime()
    highSpeedClock = 0
  }

  override def updateHighSpeedClock(): Unit = {
    // TODO: Update wall clock once in a while
    highSpeedClock = System.nanoTime() - highSpeedClockOffset
  }

  override def wallClockPart: Long = wallClock
  override def highSpeedPart: Long = highSpeedClock
}

/**
 * INTERNAL API
 */
private[remote] object RollingEventLogSection {
  val HeadPointerOffset = 0L
  val LogStateOffset = 8L
  val RecordsOffset = 16L
  val LogOffset = 0L

  // Log states
  val Empty = 0
  val Live = 1
  val Snapshot = 2

  // Slot states
  val Committed = 0
  val Dirty = 1

  val CommitEntrySize = 4
}

/**
 * INTERNAL API
 */
private[remote] class RollingEventLogSection(
    fileChannel: FileChannel,
    offset: Long,
    entryCount: Long,
    logBufferSize: Long,
    recordSize: Int) {
  import RollingEventLogSection._

  require(entryCount > 0, "entryCount must be greater than 0")
  require((entryCount & (entryCount - 1)) == 0, "entryCount must be power of two")
  private[this] val LogMask: Long = entryCount - 1L

  private[this] val buffers: Array[MappedResizeableBuffer] = Array.tabulate(FlightRecorder.SnapshotCount) { logId =>
    val buffer = new MappedResizeableBuffer(fileChannel, offset + logId * logBufferSize, logBufferSize)
    // Clear old data
    buffer.setMemory(0, logBufferSize.toInt, 0.toByte)
    if (logId == 0) buffer.putLong(LogStateOffset, Live)
    buffer
  }

  def clear(logId: Int): Unit = buffers(logId).setMemory(0, logBufferSize.toInt, 0.toByte)

  /*
   * The logic here MUST be kept in sync with its simulated version in RollingEventLogSimulationSpec as it
   * is currently the best place to do in-depth stress-testing of this logic. Unfortunately currently there is no
   * sane way to use the same code here and in the test, too.
   */
  def write(logId: Int, recordBuffer: ByteBuffer): Unit = {
    val logBuffer: MappedResizeableBuffer = buffers(logId)

    @tailrec def writeRecord(): Unit = {
      // Advance the head
      val recordOffset = RecordsOffset + ((logBuffer.getAndAddLong(HeadPointerOffset, 1L) & LogMask) * recordSize)
      val payloadOffset = recordOffset + CommitEntrySize
      // Signal that we write to the record. This is to prevent concurrent writes to the same slot
      // if the head *wraps over* and points again to this location. Without this we would end up with partial or corrupted
      // writes to the slot.
      if (logBuffer.compareAndSetInt(recordOffset, Committed, Dirty)) {
        // 128 bytes total, 4 bytes used for Commit/Dirty flag
        logBuffer.putBytes(payloadOffset, recordBuffer, recordSize - 4)
        //println(logBuffer.getLong(recordOffset + 4))

        // Now this is free to be overwritten
        logBuffer.putIntVolatile(recordOffset, Committed)
      } else writeRecord() // Try to claim a new slot
    }

    writeRecord()
  }

  def markSnapshot(logId: Int): Unit = buffers(logId).putLongVolatile(LogStateOffset, Snapshot)
  def markLive(logId: Int): Unit = buffers(logId).putLongVolatile(LogStateOffset, Live)

  def close(): Unit = buffers.foreach(_.close())
}

/**
 * INTERNAL API
 */
private[remote] object FlightRecorder {

  /**
   * @return A created file where the flight recorder file can be written. There are three options, depending
   *         on ``destination``:
   *         1. Empty: a file will be generated in the temporary directory of the OS
   *         2. A relative or absolute path ending with ".afr": this file will be used
   *         3. A relative or absolute path: this directory will be used, the file will get a random file name
   */
  def createFlightRecorderFile(destination: String, fs: FileSystem = FileSystems.getDefault): Path = {

    // TODO safer file permissions (e.g. only user readable on POSIX)?
    destination match {
      // not defined, use temporary directory
      case "" => Files.createTempFile("artery", ".afr")

      case directory if directory.endsWith(".afr") =>
        val path = fs.getPath(directory).toAbsolutePath
        if (!Files.exists(path)) {
          Files.createDirectories(path.getParent)
          Files.createFile(path)
        }
        path

      case directory =>
        val path = fs.getPath(directory).toAbsolutePath
        if (!Files.exists(path)) Files.createDirectories(path)

        Files.createTempFile(path, "artery", ".afr")
    }
  }

  def prepareFileForFlightRecorder(path: Path): FileChannel = {
    // Force the size, otherwise memory mapping will fail on *nixes
    val randomAccessFile = new RandomAccessFile(path.toFile, "rwd")
    randomAccessFile.setLength(FlightRecorder.TotalSize)
    randomAccessFile.close()

    FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)
  }

  val Alignment = 64 * 1024 // Windows is picky about mapped section alignments

  val MagicString = 0x31524641 // "AFR1", little-endian
  val GlobalSectionSize = BitUtil.align(24, Alignment)
  val StartTimeStampOffset = 4

  val LogHeaderSize = 16
  val SnapshotCount = 4
  val SnapshotMask = SnapshotCount - 1

  // TODO: Dummy values right now, format is under construction
  val AlertRecordSize = 128
  val LoFreqRecordSize = 128
  val HiFreqBatchSize = 62
  val HiFreqRecordSize = 16 * (HiFreqBatchSize + 2) // (batched events + header)

  val AlertWindow = 256
  val LoFreqWindow = 256
  val HiFreqWindow = 256 // This is counted in batches !

  val AlertLogSize = BitUtil.align(LogHeaderSize + (AlertWindow * AlertRecordSize), Alignment)
  val LoFreqLogSize = BitUtil.align(LogHeaderSize + (LoFreqWindow * LoFreqRecordSize), Alignment)
  val HiFreqLogSize = BitUtil.align(LogHeaderSize + (HiFreqWindow * HiFreqRecordSize), Alignment)

  val AlertSectionSize = AlertLogSize * SnapshotCount
  val LoFreqSectionSize = LoFreqLogSize * SnapshotCount
  val HiFreqSectionSize = HiFreqLogSize * SnapshotCount

  val AlertSectionOffset = GlobalSectionSize
  val LoFreqSectionOffset = GlobalSectionSize + AlertSectionSize
  val HiFreqSectionOffset = GlobalSectionSize + AlertSectionSize + LoFreqSectionSize

  val TotalSize = GlobalSectionSize + AlertSectionSize + LoFreqSectionSize + HiFreqSectionSize

  val HiFreqEntryCountFieldOffset = 16
}

/**
 * INTERNAL API
 */
private[remote] sealed trait FlightRecorderStatus
private[remote] case object Running extends FlightRecorderStatus
private[remote] case object ShutDown extends FlightRecorderStatus
private[remote] final case class SnapshotInProgress(latch: CountDownLatch) extends FlightRecorderStatus

/**
 * INTERNAL API
 */
private[remote] class FlightRecorder(val fileChannel: FileChannel)
    extends AtomicReference[FlightRecorderStatus](Running) {
  import FlightRecorder._

  private[this] val globalSection = new MappedResizeableBuffer(fileChannel, 0, GlobalSectionSize)

  require(SnapshotCount > 0, "SnapshotCount must be greater than 0")
  require((SnapshotCount & (SnapshotCount - 1)) == 0, "SnapshotCount must be power of two")
  private[this] val SnapshotMask = SnapshotCount - 1
  private[this] val alertLogs =
    new RollingEventLogSection(
      fileChannel = fileChannel,
      offset = AlertSectionOffset,
      entryCount = AlertWindow,
      logBufferSize = AlertLogSize,
      recordSize = AlertRecordSize)
  private[this] val loFreqLogs =
    new RollingEventLogSection(
      fileChannel = fileChannel,
      offset = LoFreqSectionOffset,
      entryCount = LoFreqWindow,
      logBufferSize = LoFreqLogSize,
      recordSize = LoFreqRecordSize)
  private[this] val hiFreqLogs =
    new RollingEventLogSection(
      fileChannel = fileChannel,
      offset = HiFreqSectionOffset,
      entryCount = HiFreqWindow,
      logBufferSize = HiFreqLogSize,
      recordSize = HiFreqRecordSize)
  // No need for volatile, guarded by atomic CAS and set
  @volatile private var currentLog = 0

  init()

  private def init(): Unit = {
    globalSection.putInt(0, MagicString)
    globalSection.putLong(StartTimeStampOffset, System.currentTimeMillis())
  }

  def snapshot(): Unit = {
    // Coalesce concurrent snapshot requests into one, i.e. ignore the "late-comers".
    // In other words, this is a critical section in which participants either enter, or just
    // simply skip ("Hm, seems someone else already does it. ¯\_(ツ)_/¯ ")
    val snapshotLatch = new CountDownLatch(1)
    val snapshotInProgress = SnapshotInProgress(snapshotLatch)
    if (compareAndSet(Running, snapshotInProgress)) {
      val previousLog = currentLog
      val nextLog = (currentLog + 1) & SnapshotMask
      // Mark new log as Live
      hiFreqLogs.clear(nextLog)
      loFreqLogs.clear(nextLog)
      alertLogs.clear(nextLog)
      hiFreqLogs.markLive(nextLog)
      loFreqLogs.markLive(nextLog)
      alertLogs.markLive(nextLog)
      // Redirect traffic to newly allocated log
      currentLog = nextLog
      // Mark previous log as snapshot
      hiFreqLogs.markSnapshot(previousLog)
      loFreqLogs.markSnapshot(previousLog)
      alertLogs.markSnapshot(previousLog)
      fileChannel.force(true)
      snapshotLatch.countDown()
      compareAndSet(snapshotInProgress, Running)
      // At this point it is NOT GUARANTEED that all writers have finished writing to the currently snapshotted
      // buffer!
    }
  }

  def close(): Unit = {
    getAndSet(ShutDown) match {
      case SnapshotInProgress(latch) => latch.await(3, TimeUnit.SECONDS)
      case _                         => // Nothing to unlock
    }
    alertLogs.close()
    hiFreqLogs.close()
    loFreqLogs.close()
    globalSection.close()
  }

  def createEventSink(): EventSink = new EventSink {
    private[this] val clock = new EventClockImpl
    private[this] val alertRecordBuffer = ByteBuffer.allocate(AlertRecordSize).order(ByteOrder.LITTLE_ENDIAN)
    private[this] val loFreqRecordBuffer = ByteBuffer.allocate(LoFreqRecordSize).order(ByteOrder.LITTLE_ENDIAN)
    private[this] val hiFreqBatchBuffer = ByteBuffer.allocate(HiFreqRecordSize).order(ByteOrder.LITTLE_ENDIAN)
    private[this] var hiFreqBatchedEntries = 0L

    startHiFreqBatch()

    override def alert(code: Int, metadata: Array[Byte]): Unit = {
      if (FlightRecorder.this.get eq Running) {
        clock.updateWallClock()
        prepareRichRecord(alertRecordBuffer, code, metadata)
        alertLogs.write(currentLog, alertRecordBuffer)
        flushHiFreqBatch()
        snapshot()
      }
    }

    override def alert(code: Int, metadata: String): Unit = {
      alert(code, metadata.getBytes("US-ASCII"))
    }

    override def loFreq(code: Int, metadata: Array[Byte]): Unit = {
      val status = FlightRecorder.this.get
      if (status eq Running) {
        clock.updateHighSpeedClock()
        prepareRichRecord(loFreqRecordBuffer, code, metadata)
        loFreqLogs.write(currentLog, loFreqRecordBuffer)
      }
    }

    override def loFreq(code: Int, metadata: String): Unit = {
      loFreq(code, metadata.getBytes("US-ASCII"))
    }

    private def prepareRichRecord(recordBuffer: ByteBuffer, code: Int, metadata: Array[Byte]): Unit = {
      recordBuffer.clear()
      // TODO: This is a bit overkill, needs some smarter scheme later, no need to always store the wallclock
      recordBuffer.putLong(clock.wallClockPart)
      recordBuffer.putLong(clock.highSpeedPart)
      recordBuffer.putInt(code)
      // Truncate if necessary
      val metadataLength = math.min(LoFreqRecordSize - 32, metadata.length)
      recordBuffer.put(metadataLength.toByte)
      if (metadataLength > 0)
        recordBuffer.put(metadata, 0, metadataLength)
      // Don't flip here! We always write fixed size records
      recordBuffer.position(0)
    }

    // TODO: Try to save as many bytes here as possible! We will see crazy throughput here
    override def hiFreq(code: Long, param: Long): Unit = {
      val status = FlightRecorder.this.get
      if (status eq Running) {
        hiFreqBatchedEntries += 1
        hiFreqBatchBuffer.putLong(code)
        hiFreqBatchBuffer.putLong(param)

        // If batch is full, time to flush
        if (!hiFreqBatchBuffer.hasRemaining) flushHiFreqBatch()
      }
    }

    private def startHiFreqBatch(): Unit = {
      hiFreqBatchBuffer.clear()
      // Refresh the nanotime
      clock.updateHighSpeedClock()
      // Header of the batch will contain our most accurate knowledge of the clock, individual entries do not
      // contain any timestamp
      hiFreqBatchBuffer.putLong(clock.wallClockPart)
      hiFreqBatchBuffer.putLong(clock.highSpeedPart)
      // Leave space for the size field
      hiFreqBatchBuffer.putLong(0L)
      // Reserved for now
      hiFreqBatchBuffer.putLong(0L)
      // Mow ready to write some more events...
    }

    override def flushHiFreqBatch(): Unit = {
      val status = FlightRecorder.this.get
      if (status eq Running) {
        if (hiFreqBatchedEntries > 0) {
          hiFreqBatchBuffer.putLong(HiFreqEntryCountFieldOffset, hiFreqBatchedEntries)
          hiFreqBatchedEntries = 0
          hiFreqBatchBuffer.position(0)
          hiFreqLogs.write(currentLog, hiFreqBatchBuffer)
          startHiFreqBatch()
        }
      }
    }

  }
}
