package akka.remote.artery

import java.io.{ IOException, RandomAccessFile }
import java.nio.channels.FileChannel
import java.nio.file.{ FileSystems, Path }
import java.time.Instant

import org.agrona.concurrent.MappedResizeableBuffer

import scala.collection.{ SortedSet, immutable }

/**
 * Internal API
 *
 * Minimal utility for dumping a given afr file as text to stdout
 */
object FlightRecorderDump extends App {
  require(args.size == 1, "Usage: FlightRecorderDump afr-file")
  val path = FileSystems.getDefault.getPath(args(0))
  FlightRecorderReader.dumpToStdout(path)
}

/**
 * Internal API
 */
private[akka] object FlightRecorderReader {
  import FlightRecorder._

  sealed trait LogState
  case object Empty extends LogState
  case object Live extends LogState
  case object Snapshot extends LogState

  case class SectionParameters(
    offset:           Long,
    sectionSize:      Long,
    logSize:          Long,
    window:           Long,
    recordSize:       Long,
    entriesPerRecord: Long) {
    override def toString: String =
      s"""
         |  offset             = $offset
         |  size               = $sectionSize
         |  log size           = $logSize
         |  window             = $window
         |  record size        = $recordSize
         |  max Entries/Record = $entriesPerRecord
         |  max Total Entries  = ${entriesPerRecord * window}
       """.stripMargin
  }

  val AlertSectionParameters = SectionParameters(
    offset = AlertSectionOffset,
    sectionSize = AlertSectionSize,
    logSize = AlertLogSize,
    window = AlertWindow,
    recordSize = AlertRecordSize,
    entriesPerRecord = 1)

  val LoFreqSectionParameters = SectionParameters(
    offset = LoFreqSectionOffset,
    sectionSize = LoFreqSectionSize,
    logSize = LoFreqLogSize,
    window = LoFreqWindow,
    recordSize = LoFreqRecordSize,
    entriesPerRecord = 1)

  val HiFreqSectionParameters = SectionParameters(
    offset = HiFreqSectionOffset,
    sectionSize = HiFreqSectionSize,
    logSize = HiFreqLogSize,
    window = HiFreqWindow,
    recordSize = HiFreqRecordSize,
    entriesPerRecord = HiFreqBatchSize)

  def dumpToStdout(flightRecorderFile: Path): Unit = {
    var raFile: RandomAccessFile = null
    var channel: FileChannel = null
    var reader: FlightRecorderReader = null
    try {

      raFile = new RandomAccessFile(flightRecorderFile.toFile, "rw")
      channel = raFile.getChannel
      reader = new FlightRecorderReader(channel)
      val alerts: Seq[FlightRecorderReader#Entry] = reader.structure.alertLog.logs.flatMap(_.richEntries)
      val hiFreq: Seq[FlightRecorderReader#Entry] = reader.structure.hiFreqLog.logs.flatMap(_.compactEntries)
      val loFreq: Seq[FlightRecorderReader#Entry] = reader.structure.loFreqLog.logs.flatMap(_.richEntries)

      implicit val ordering = Ordering.fromLessThan[FlightRecorderReader#Entry]((a, b) ⇒ a.timeStamp.isBefore(b.timeStamp))
      val sorted = SortedSet[FlightRecorderReader#Entry](alerts: _*) ++ hiFreq ++ loFreq

      println("--- FLIGHT RECORDER LOG")
      sorted.foreach(println)

    } finally {
      if (reader ne null) reader.close()
      if (channel ne null) channel.close()
      if (raFile ne null) raFile.close()
    }
  }

}

/**
 * Internal API
 */
private[akka] final class FlightRecorderReader(fileChannel: FileChannel) {
  import FlightRecorder._
  import FlightRecorderReader._

  case class Structure(startTime: Instant, alertLog: RollingLog, loFreqLog: RollingLog, hiFreqLog: RollingLog) {
    override def toString: String =
      s"""
        |AFR file created at $startTime
        |Total size : $TotalSize
        |
        |--- ALERTS
        |$alertLog
        |--- LOW FREQUENCY EVENTS
        |$loFreqLog
        |--- HIGH FREQUENCY EVENTS
        |$hiFreqLog
      """.stripMargin
  }

  case class RollingLog(sectionParameters: SectionParameters, logs: immutable.Seq[Log]) {
    override def toString: String =
      s"""
         |$sectionParameters
         |
         |${logs.mkString("  ", "\n  ", "")}
       """.stripMargin
  }

  case class Log(sectionParameters: SectionParameters, offset: Long, id: Int, state: LogState, head: Long) {
    override def toString: String = s"$id: $state \thead = $head  (Offset: $offset Size: ${sectionParameters.logSize})"

    def richEntries: Iterator[RichEntry] = {
      new Iterator[RichEntry] {
        var recordOffset = offset + RollingEventLogSection.RecordsOffset
        var recordsLeft = math.min(head, sectionParameters.window)

        override def hasNext: Boolean = recordsLeft > 0

        override def next(): RichEntry = {
          val recordStartOffset = recordOffset + RollingEventLogSection.CommitEntrySize

          // FIXME: extract magic numbers
          val metadata = Array.ofDim[Byte](fileBuffer.getByte(recordStartOffset + 20))
          fileBuffer.getBytes(recordStartOffset + 21, metadata)

          val entry = RichEntry(
            timeStamp = Instant.ofEpochMilli(fileBuffer.getLong(recordStartOffset)).plusNanos(fileBuffer.getLong(recordStartOffset + 8)),
            dirty = fileBuffer.getLong(recordOffset) == RollingEventLogSection.Dirty,
            code = fileBuffer.getInt(recordStartOffset + 16),
            metadata = metadata)
          recordOffset += sectionParameters.recordSize
          recordsLeft -= 1
          entry
        }
      }
    }

    def compactEntries: Iterator[CompactEntry] = {
      new Iterator[CompactEntry] {
        var recordOffset = offset + RollingEventLogSection.RecordsOffset
        var entryOffset = recordOffset + RollingEventLogSection.CommitEntrySize
        var recordsLeft = math.min(head, sectionParameters.window)
        var entriesLeft = -1L
        var dirty = false
        var timeStamp: Instant = _

        private def readHeader(): Unit = {
          dirty = fileBuffer.getLong(recordOffset) == RollingEventLogSection.Dirty
          val entiresHeaderOffset = recordOffset + RollingEventLogSection.CommitEntrySize
          entriesLeft = fileBuffer.getLong(entiresHeaderOffset + HiFreqEntryCountFieldOffset)
          timeStamp = Instant.ofEpochMilli(fileBuffer.getLong(entiresHeaderOffset))
            .plusNanos(fileBuffer.getLong(entiresHeaderOffset + 8))
          entryOffset = entiresHeaderOffset + 32
        }

        override def hasNext: Boolean = recordsLeft > 0

        override def next(): CompactEntry = {
          if (entriesLeft == -1L) readHeader()

          val entry = CompactEntry(
            timeStamp,
            dirty,
            code = fileBuffer.getLong(entryOffset),
            param = fileBuffer.getLong(entryOffset + 8))

          entriesLeft -= 1
          if (entriesLeft == 0) {
            recordOffset += sectionParameters.recordSize
            recordsLeft -= 1
            readHeader()
          } else {
            entryOffset += 16
          }

          entry
        }
      }
    }
  }

  trait Entry {
    def timeStamp: Instant
  }

  case class RichEntry(timeStamp: Instant, dirty: Boolean, code: Long, metadata: Array[Byte]) extends Entry {
    override def toString: String = {
      val textualCode = FlightRecorderEvents.eventDictionary.getOrElse(code, "").take(34)
      val metadataString = new String(metadata, "US-ASCII")
      f"[$timeStamp] ${if (dirty) "#" else ""} $code%3s $textualCode%-34s | $metadataString"
    }
  }

  case class CompactEntry(timeStamp: Instant, dirty: Boolean, code: Long, param: Long) extends Entry {
    override def toString: String = {
      val textualCode = FlightRecorderEvents.eventDictionary.getOrElse(code, "").take(34)
      f"[$timeStamp] ${if (dirty) "#" else ""} $code%3s $textualCode%-34s | $param"
    }
  }

  private val fileBuffer = new MappedResizeableBuffer(fileChannel, 0, TotalSize)
  private var _structure: Structure = _
  rereadStructure()

  def rereadStructure(): Unit = {
    if (fileBuffer.getInt(0) != MagicString) {
      fileBuffer.close()
      throw new IOException(s"Expected magic string AFR1 (0x31524641) but got ${fileBuffer.getInt(0)}")
    }

    val alertLog = readRollingLog(AlertSectionParameters)
    val loFreqLog = readRollingLog(LoFreqSectionParameters)
    val hiFreqLog = readRollingLog(HiFreqSectionParameters)

    _structure = Structure(Instant.ofEpochMilli(fileBuffer.getLong(4)), alertLog, loFreqLog, hiFreqLog)
  }

  private def readRollingLog(sectionParameters: SectionParameters): RollingLog = {
    val logs = Vector.tabulate(SnapshotCount) { idx ⇒
      readLog(idx, sectionParameters.offset + (idx * sectionParameters.logSize), sectionParameters)
    }
    RollingLog(sectionParameters, logs)
  }

  private def readLog(id: Int, offset: Long, sectionParameters: SectionParameters): Log = {
    val state = fileBuffer.getLong(offset + RollingEventLogSection.LogStateOffset) match {
      case RollingEventLogSection.Empty    ⇒ Empty
      case RollingEventLogSection.Live     ⇒ Live
      case RollingEventLogSection.Snapshot ⇒ Snapshot
      case other                           ⇒ throw new IOException(s"Unrecognized log state: $other in log at offset $offset")
    }
    Log(sectionParameters, offset, id, state, fileBuffer.getLong(offset + RollingEventLogSection.HeadPointerOffset))
  }

  def structure: Structure = _structure

  def close(): Unit = fileBuffer.close()

}
