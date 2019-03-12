/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import java.io.{ File, IOException, RandomAccessFile }
import java.nio.channels.FileChannel
import java.nio.file.{ Files, Path, StandardOpenOption }
import java.time.Instant
import java.util.Arrays
import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.testkit.AkkaSpec
import com.google.common.jimfs.{ Configuration, Jimfs }

class FlightRecorderSpec extends AkkaSpec {
  import FlightRecorderReader._

  "Flight Recorder" must {

    "properly initialize AFR file when created" in withFlightRecorder { (_, reader, channel) =>
      channel.force(false)

      // otherwise isAfter assertion below can randomly fail
      Thread.sleep(1)
      val currentTime = Instant.now()

      reader.rereadStructure()

      currentTime.isAfter(reader.structure.startTime) should be(true)
      (currentTime.toEpochMilli - reader.structure.startTime.toEpochMilli < 3000) should be(true)

      reader.structure.alertLog.logs.size should ===(FlightRecorder.SnapshotCount)
      reader.structure.loFreqLog.logs.size should ===(FlightRecorder.SnapshotCount)
      reader.structure.hiFreqLog.logs.size should ===(FlightRecorder.SnapshotCount)

      def checkLogInitialized(log: reader.RollingLog): Unit = {
        log.logs(0).state should ===(Live)
        log.logs(0).head should ===(0L)
        log.logs(0).richEntries.toSeq should ===(Nil)

        log.logs(1).state should ===(Empty)
        log.logs(1).head should ===(0L)
        log.logs(1).richEntries.toSeq should ===(Nil)

        log.logs(2).state should ===(Empty)
        log.logs(2).head should ===(0L)
        log.logs(2).richEntries.toSeq should ===(Nil)

        log.logs(3).state should ===(Empty)
        log.logs(3).head should ===(0L)
        log.logs(3).richEntries.toSeq should ===(Nil)
      }

      checkLogInitialized(reader.structure.alertLog)
      checkLogInitialized(reader.structure.loFreqLog)
      checkLogInitialized(reader.structure.hiFreqLog)
    }

    "properly rotate logs when snapshotting" in withFlightRecorder { (recorder, reader, channel) =>
      recorder.snapshot()
      channel.force(false)
      reader.rereadStructure()

      def checkLogRotated(log: reader.RollingLog, states: Seq[LogState]): Unit =
        log.logs.zip(states).foreach { case (log, state) => log.state should ===(state) }

      checkLogRotated(reader.structure.alertLog, List(Snapshot, Live, Empty, Empty))
      checkLogRotated(reader.structure.loFreqLog, List(Snapshot, Live, Empty, Empty))
      checkLogRotated(reader.structure.hiFreqLog, List(Snapshot, Live, Empty, Empty))

      recorder.snapshot()
      reader.rereadStructure()

      checkLogRotated(reader.structure.alertLog, List(Snapshot, Snapshot, Live, Empty))
      checkLogRotated(reader.structure.loFreqLog, List(Snapshot, Snapshot, Live, Empty))
      checkLogRotated(reader.structure.hiFreqLog, List(Snapshot, Snapshot, Live, Empty))

      recorder.snapshot()
      recorder.snapshot()
      reader.rereadStructure()

      checkLogRotated(reader.structure.alertLog, List(Live, Snapshot, Snapshot, Snapshot))
      checkLogRotated(reader.structure.loFreqLog, List(Live, Snapshot, Snapshot, Snapshot))
      checkLogRotated(reader.structure.hiFreqLog, List(Live, Snapshot, Snapshot, Snapshot))
    }

    "properly report zero low frequency events" in withFlightRecorder { (recorder, reader, channel) =>
      channel.force(false)
      reader.rereadStructure()

      val entries = reader.structure.loFreqLog.logs(0).richEntries.toSeq

      entries.isEmpty should be(true)
    }

    "properly report zero high frequency events" in withFlightRecorder { (recorder, reader, channel) =>
      channel.force(false)
      reader.rereadStructure()

      val entries = reader.structure.hiFreqLog.logs(0).compactEntries.toSeq

      entries.isEmpty should be(true)
    }

    "properly store one low frequency event" in withFlightRecorder { (recorder, reader, channel) =>
      val sink = recorder.createEventSink()
      val helloBytes = "Hello".getBytes("US-ASCII")

      sink.loFreq(42, helloBytes)
      channel.force(false)

      reader.rereadStructure()
      val entries = reader.structure.loFreqLog.logs(0).richEntries.toSeq

      entries.exists(_.dirty) should be(false)
      entries.map(_.code.toInt) should ===(List(42))
    }

    "properly store one high frequency event" in withFlightRecorder { (recorder, reader, channel) =>
      val sink = recorder.createEventSink()

      sink.hiFreq(42, 64)
      sink.flushHiFreqBatch()
      channel.force(false)

      reader.rereadStructure()
      val entries = reader.structure.hiFreqLog.logs(0).compactEntries.toSeq

      entries.exists(_.dirty) should be(false)
      entries.map(_.code.toInt) should ===(List(42))
      entries.map(_.param.toInt) should ===(List(64))
    }

    "properly store low frequency events" in withFlightRecorder { (recorder, reader, channel) =>
      val sink = recorder.createEventSink()
      val helloBytes = "Hello".getBytes("US-ASCII")

      for (i <- 0 until FlightRecorder.LoFreqWindow)
        sink.loFreq(i, helloBytes)

      channel.force(false)

      reader.rereadStructure()
      val entries = reader.structure.loFreqLog.logs(0).richEntries.toSeq

      entries.exists(_.dirty) should be(false)
      entries.map(_.code.toInt) should ===(0 until FlightRecorder.LoFreqWindow)
      entries.forall(entry => Arrays.equals(entry.metadata, helloBytes)) should be(true)

      // Timestamps are monotonic
      entries.sortBy(_.code) should ===(entries.sortBy(_.timeStamp))
    }

    "properly truncate low frequency event metadata if necessary" in withFlightRecorder { (recorder, reader, channel) =>
      val sink = recorder.createEventSink()
      val longMetadata = new Array[Byte](1024)

      sink.loFreq(0, longMetadata)
      channel.force(false)

      reader.rereadStructure()
      val entries = reader.structure.loFreqLog.logs(0).richEntries.toSeq

      entries.size should ===(1)
      entries.head.metadata should ===(new Array[Byte](FlightRecorder.LoFreqRecordSize - 32))

    }

    "properly store high frequency events" in withFlightRecorder { (recorder, reader, channel) =>
      val EffectiveHighFreqWindow = FlightRecorder.HiFreqWindow * FlightRecorder.HiFreqBatchSize
      val sink = recorder.createEventSink()

      for (i <- 0 until EffectiveHighFreqWindow)
        sink.hiFreq(i, 42)

      sink.flushHiFreqBatch()
      channel.force(false)

      reader.rereadStructure()
      val entries = reader.structure.hiFreqLog.logs(0).compactEntries.toSeq

      entries.exists(_.dirty) should be(false)
      entries.map(_.code.toInt) should ===(0 until EffectiveHighFreqWindow)
      entries.forall(entry => entry.param == 42) should be(true)

      // Timestamps are monotonic
      entries.sortBy(_.code) should ===(entries.sortBy(_.timeStamp))
    }

    "properly store and rotate low frequency events" in withFlightRecorder { (recorder, reader, channel) =>
      val sink = recorder.createEventSink()
      val helloBytes = "Hello".getBytes("US-ASCII")

      for (i <- 0 until FlightRecorder.LoFreqWindow + 100)
        sink.loFreq(i, helloBytes)

      channel.force(false)

      reader.rereadStructure()
      val entries = reader.structure.loFreqLog.logs(0).richEntries.toSeq

      entries.exists(_.dirty) should be(false)
      entries.map(_.code.toInt).sorted should ===(100 until (FlightRecorder.LoFreqWindow + 100))
      entries.forall(entry => Arrays.equals(entry.metadata, helloBytes)) should be(true)

      // Timestamps are monotonic
      entries.sortBy(_.code) should ===(entries.sortBy(_.timeStamp))
    }

    "properly store and rotate high frequency events" in withFlightRecorder { (recorder, reader, channel) =>
      val EffectiveHighFreqWindow = FlightRecorder.HiFreqWindow * FlightRecorder.HiFreqBatchSize
      val sink = recorder.createEventSink()

      for (i <- 0 until EffectiveHighFreqWindow + 100)
        sink.hiFreq(i, 42)

      sink.flushHiFreqBatch()
      channel.force(false)

      reader.rereadStructure()
      val entries = reader.structure.hiFreqLog.logs(0).compactEntries.toSeq

      entries.exists(_.dirty) should be(false)
      // Note the (2 * FlightRecorder.HiFreqBatchSize) initial sequence number.
      // This is because the overflow by 100 events rotates out two records, not just 100.
      entries.map(_.code.toInt).sorted should ===(
        (2 * FlightRecorder.HiFreqBatchSize) until (EffectiveHighFreqWindow + 100))
      entries.forall(entry => entry.param == 42) should be(true)

      // Timestamps are monotonic
      entries.sortBy(_.code) should ===(entries.sortBy(_.timeStamp))
    }

    "properly store low frequency events after snapshot" in withFlightRecorder { (recorder, reader, channel) =>
      val sink = recorder.createEventSink()
      val helloBytes = "Hello".getBytes("US-ASCII")
      val hello2Bytes = "Hello2".getBytes("US-ASCII")

      for (i <- 0 until 100)
        sink.loFreq(i, helloBytes)

      recorder.snapshot()

      for (i <- 0 until 50)
        sink.loFreq(i, hello2Bytes)

      reader.rereadStructure()

      reader.structure.loFreqLog.logs(0).state should ===(Snapshot)
      reader.structure.loFreqLog.logs(1).state should ===(Live)

      val snapshotEntries = reader.structure.loFreqLog.logs(0).richEntries.toSeq
      val liveEntries = reader.structure.loFreqLog.logs(1).richEntries.toSeq

      snapshotEntries.exists(_.dirty) should be(false)
      snapshotEntries.map(_.code.toInt) should ===(0 until 100)
      snapshotEntries.forall(entry => Arrays.equals(entry.metadata, helloBytes)) should be(true)

      // Timestamps are monotonic
      snapshotEntries.sortBy(_.code) should ===(snapshotEntries.sortBy(_.timeStamp))

      liveEntries.exists(_.dirty) should be(false)
      liveEntries.map(_.code.toInt) should ===(0 until 50)
      liveEntries.forall(entry => Arrays.equals(entry.metadata, hello2Bytes)) should be(true)

      // Timestamps are monotonic
      liveEntries.sortBy(_.code) should ===(liveEntries.sortBy(_.timeStamp))
    }

    "properly store high frequency events after snapshot" in withFlightRecorder { (recorder, reader, channel) =>
      val sink = recorder.createEventSink()

      for (i <- 0 until 100)
        sink.hiFreq(i, 0)

      sink.flushHiFreqBatch()
      recorder.snapshot()

      for (i <- 0 until 50)
        sink.hiFreq(i, 1)

      sink.flushHiFreqBatch()
      channel.force(false)
      reader.rereadStructure()

      reader.structure.hiFreqLog.logs(0).state should ===(Snapshot)
      reader.structure.hiFreqLog.logs(1).state should ===(Live)

      val snapshotEntries = reader.structure.hiFreqLog.logs(0).compactEntries.toSeq
      val liveEntries = reader.structure.hiFreqLog.logs(1).compactEntries.toSeq

      snapshotEntries.exists(_.dirty) should be(false)
      snapshotEntries.map(_.code.toInt) should ===(0 until 100)
      snapshotEntries.forall(_.param == 0) should be(true)

      // Timestamps are monotonic
      snapshotEntries.sortBy(_.code) should ===(snapshotEntries.sortBy(_.timeStamp))

      liveEntries.exists(_.dirty) should be(false)
      liveEntries.map(_.code.toInt) should ===(0 until 50)
      liveEntries.forall(_.param == 1) should be(true)

      // Timestamps are monotonic
      liveEntries.sortBy(_.code) should ===(liveEntries.sortBy(_.timeStamp))
    }

    "properly store alerts and make a snapshot" in withFlightRecorder { (recorder, reader, channel) =>
      val sink = recorder.createEventSink()
      val helloBytes = "Hello".getBytes("US-ASCII")
      val alertBytes = "An alert".getBytes("US-ASCII")

      for (i <- 0 until 100) {
        sink.hiFreq(i, 1)
        sink.loFreq(i, helloBytes)
      }

      sink.alert(42, alertBytes)
      reader.rereadStructure()

      // Snapshot is automatically taken
      reader.structure.alertLog.logs(0).state should ===(Snapshot)
      reader.structure.loFreqLog.logs(0).state should ===(Snapshot)
      reader.structure.hiFreqLog.logs(0).state should ===(Snapshot)
      reader.structure.alertLog.logs(1).state should ===(Live)
      reader.structure.loFreqLog.logs(1).state should ===(Live)
      reader.structure.hiFreqLog.logs(1).state should ===(Live)

      val hiFreqEntries = reader.structure.hiFreqLog.logs(0).compactEntries.toSeq
      val loFreqEntries = reader.structure.loFreqLog.logs(0).richEntries.toSeq
      val alertEntries = reader.structure.alertLog.logs(0).richEntries.toSeq

      // High frequency events are flushed (100 leaves an uncomplete batch if not flushed,
      // i.e. only the first batch visible if alert did not flush)
      hiFreqEntries.map(_.code.toInt) should ===(0 until 100)
      hiFreqEntries.forall(_.param == 1) should be(true)
      loFreqEntries.map(_.code.toInt) should ===(0 until 100)
      loFreqEntries.forall(entry => Arrays.equals(entry.metadata, helloBytes)) should be(true)
      alertEntries.map(_.code.toInt) should ===(List(42))
      Arrays.equals(alertEntries.head.metadata, alertBytes) should be(true)
    }

    "properly store events from multiple threads" in withFlightRecorder { (recorder, reader, channel) =>
      val Threads = 4
      val startLatch = new CountDownLatch(1)
      val finishLatch = new CountDownLatch(Threads)

      for (i <- 1 to Threads) {
        new Thread {
          override def run(): Unit = {
            val sink = recorder.createEventSink()
            startLatch.await(3, TimeUnit.SECONDS)

            for (j <- 0 until 100) sink.loFreq(code = i, Array(j.toByte))
            finishLatch.countDown()
          }
        }.start()
      }

      startLatch.countDown()
      finishLatch.await(3, TimeUnit.SECONDS)
      channel.force(false)
      reader.rereadStructure()

      reader.structure.loFreqLog.logs.head.richEntries.size should ===(FlightRecorder.LoFreqWindow)

      for (i <- 1 to Threads) {
        val entries = reader.structure.loFreqLog.logs.head.richEntries.filter(_.code == i).toSeq

        entries.exists(_.dirty) should be(false)
        // Entries are consecutive for any given writer
        entries.map(_.metadata(0).toInt).sorted should ===((100 - entries.size) until 100)
        entries.forall(_.code == i) should be(true)

        // Timestamps are monotonic
        entries.sortBy(_.metadata(0).toInt) should ===(entries.sortBy(_.timeStamp))
      }
    }

    "create flight recorder file" in {
      def assertFileIsSound(path: Path) = {
        Files.exists(path) should ===(true)
        Files.isRegularFile(path) should ===(true)
        Files.isWritable(path) should ===(true)
        Files.isReadable(path) should ===(true)
      }
      val fs = Jimfs.newFileSystem(Configuration.unix())

      try {
        val tmpPath = FlightRecorder.createFlightRecorderFile("", fs)
        assertFileIsSound(tmpPath)
        // this is likely in the actual file system, so lets delete it
        Files.delete(tmpPath)

        Files.createDirectory(fs.getPath("/directory"))
        val tmpFileInGivenPath = FlightRecorder.createFlightRecorderFile("/directory", fs)
        assertFileIsSound(tmpFileInGivenPath)

        val specificFile = FlightRecorder.createFlightRecorderFile("/directory/flight-recorder.afr", fs)
        assertFileIsSound(specificFile)

      } finally {
        fs.close()
      }

    }

  }

  private def withFlightRecorder(body: (FlightRecorder, FlightRecorderReader, FileChannel) => Unit): Unit = {
    val file = File.createTempFile("artery", ".afr")
    file.deleteOnExit()

    var randomAccessFile: RandomAccessFile = null
    var recorder: FlightRecorder = null
    var reader: FlightRecorderReader = null
    var channel: FileChannel = null

    try {
      randomAccessFile = new RandomAccessFile(file, "rwd")
      randomAccessFile.setLength(FlightRecorder.TotalSize)
      randomAccessFile.close()

      channel =
        FileChannel.open(file.toPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)
      recorder = new FlightRecorder(channel)
      reader = new FlightRecorderReader(channel)
      body(recorder, reader, channel)
    } finally {
      // Try to delete anyway
      try {
        if (randomAccessFile ne null) randomAccessFile.close()
        if (recorder ne null) recorder.close()
        if (reader ne null) reader.close()
        if (channel ne null) channel.close()
        file.delete()
      } catch { case e: IOException => e.printStackTrace() }
    }
  }

}
