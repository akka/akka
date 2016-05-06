/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import io.aeron.Aeron
import io.aeron.driver.MediaDriver
import java.util.concurrent.Executors
import scala.util.Success
import scala.util.Failure
import scala.concurrent.Future
import akka.Done
import org.HdrHistogram.Histogram
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicLongArray
import akka.stream.ThrottleMode
import org.agrona.ErrorHandler
import io.aeron.AvailableImageHandler
import io.aeron.UnavailableImageHandler
import io.aeron.Image
import io.aeron.AvailableImageHandler
import akka.actor.ExtendedActorSystem
import java.io.File
import io.aeron.CncFileDescriptor

object AeronStreamsApp {

  val channel1 = "aeron:udp?endpoint=localhost:40123"
  val channel2 = "aeron:udp?endpoint=localhost:40124"
  val streamId = 1
  val throughputN = 10000000
  val latencyRate = 10000 // per second
  val latencyN = 10 * latencyRate
  val payload = ("0" * 100).getBytes("utf-8")
  lazy val sendTimes = new AtomicLongArray(latencyN)

  lazy val driver = {
    val driverContext = new MediaDriver.Context
    driverContext.clientLivenessTimeoutNs(SECONDS.toNanos(10))
    driverContext.imageLivenessTimeoutNs(SECONDS.toNanos(10))
    driverContext.driverTimeoutMs(SECONDS.toNanos(10))
    MediaDriver.launchEmbedded(driverContext)
  }

  lazy val stats = {
    new AeronStat(AeronStat.mapCounters(new File(driver.aeronDirectoryName, CncFileDescriptor.CNC_FILE)))
  }

  lazy val aeron = {
    val ctx = new Aeron.Context
    ctx.errorHandler(new ErrorHandler {
      override def onError(cause: Throwable) {
        println(s"# Aeron onError " + cause) // FIXME
      }
    })
    ctx.availableImageHandler(new AvailableImageHandler {
      override def onAvailableImage(img: Image): Unit = {
        println(s"onAvailableImage from ${img.sourceIdentity} session ${img.sessionId}")
      }
    })
    ctx.unavailableImageHandler(new UnavailableImageHandler {
      override def onUnavailableImage(img: Image): Unit = {
        println(s"onUnavailableImage from ${img.sourceIdentity} session ${img.sessionId}")
      }
    })

    ctx.aeronDirectoryName(driver.aeronDirectoryName)
    Aeron.connect(ctx)
  }

  lazy val system = ActorSystem("AeronStreams")
  lazy implicit val mat = ActorMaterializer()(system)

  lazy val taskRunner = {
    val r = new TaskRunner(system.asInstanceOf[ExtendedActorSystem])
    r.start()
    r
  }

  lazy val reporter = new RateReporter(SECONDS.toNanos(1), new RateReporter.Reporter {
    override def onReport(messagesPerSec: Double, bytesPerSec: Double, totalMessages: Long, totalBytes: Long): Unit = {
      println("%.03g msgs/sec, %.03g bytes/sec, totals %d messages %d MB".format(
        messagesPerSec, bytesPerSec, totalMessages, totalBytes / (1024 * 1024)))
    }
  })
  lazy val reporterExecutor = Executors.newFixedThreadPool(1)

  def stopReporter(): Unit = {
    reporter.halt()
    reporterExecutor.shutdown()
  }

  def exit(status: Int): Unit = {
    stopReporter()

    system.scheduler.scheduleOnce(10.seconds) {
      mat.shutdown()
      system.terminate()
      new Thread {
        Thread.sleep(3000)
        System.exit(status)
      }.run()
    }(system.dispatcher)
  }

  lazy val histogram = new Histogram(SECONDS.toNanos(10), 3)

  def printTotal(total: Int, pre: String, startTime: Long, payloadSize: Long): Unit = {
    val d = (System.nanoTime - startTime).nanos.toMillis
    println(f"### $total $pre of size ${payloadSize} bytes took $d ms, " +
      f"${1000.0 * total / d}%.03g msg/s, ${1000.0 * total * payloadSize / d}%.03g bytes/s")

    if (histogram.getTotalCount > 0) {
      println("Histogram of RTT latencies in microseconds.")
      histogram.outputPercentileDistribution(System.out, 1000.0)
    }
  }

  def main(args: Array[String]): Unit = {

    // receiver of plain throughput testing
    if (args.length == 0 || args(0) == "receiver")
      runReceiver()

    // sender of plain throughput testing
    if (args.length == 0 || args(0) == "sender")
      runSender()

    // sender of ping-pong latency testing
    if (args.length != 0 && args(0) == "echo-sender")
      runEchoSender()

    // echo receiver of ping-pong latency testing
    if (args.length != 0 && args(0) == "echo-receiver")
      runEchoReceiver()

    if (args(0) == "debug-receiver")
      runDebugReceiver()

    if (args(0) == "debug-sender")
      runDebugSender()

    if (args.length >= 2 && args(1) == "stats")
      runStats()
  }

  def runReceiver(): Unit = {
    import system.dispatcher
    reporterExecutor.execute(reporter)
    val r = reporter
    var t0 = System.nanoTime()
    var count = 0L
    var payloadSize = 0L
    Source.fromGraph(new AeronSource(channel1, streamId, aeron, taskRunner))
      .map { bytes ⇒
        r.onMessage(1, bytes.length)
        bytes
      }
      .runForeach { bytes ⇒
        count += 1
        if (count == 1) {
          t0 = System.nanoTime()
          payloadSize = bytes.length
        } else if (count == throughputN) {
          exit(0)
          printTotal(throughputN, "receive", t0, payloadSize)
        }
      }.onFailure {
        case e ⇒
          e.printStackTrace
          exit(-1)
      }

  }

  def runSender(): Unit = {
    reporterExecutor.execute(reporter)
    val r = reporter
    val t0 = System.nanoTime()
    Source(1 to throughputN)
      .map { n ⇒
        if (n == throughputN) {
          exit(0)
          printTotal(throughputN, "send", t0, payload.length)
        }
        n
      }
      .map { _ ⇒
        r.onMessage(1, payload.length)
        payload
      }
      .runWith(new AeronSink(channel1, streamId, aeron, taskRunner))
  }

  def runEchoReceiver(): Unit = {
    // just echo back on channel2
    reporterExecutor.execute(reporter)
    val r = reporter
    Source.fromGraph(new AeronSource(channel1, streamId, aeron, taskRunner))
      .map { bytes ⇒
        r.onMessage(1, bytes.length)
        bytes
      }
      .runWith(new AeronSink(channel2, streamId, aeron, taskRunner))
  }

  def runEchoSender(): Unit = {
    import system.dispatcher
    reporterExecutor.execute(reporter)
    val r = reporter

    val barrier = new CyclicBarrier(2)
    var repeat = 3
    val count = new AtomicInteger
    var t0 = System.nanoTime()
    Source.fromGraph(new AeronSource(channel2, streamId, aeron, taskRunner))
      .map { bytes ⇒
        r.onMessage(1, bytes.length)
        bytes
      }
      .runForeach { bytes ⇒
        val c = count.incrementAndGet()
        val d = System.nanoTime() - sendTimes.get(c - 1)
        if (c % (latencyN / 10) == 0)
          println(s"# receive offset $c => ${d / 1000} µs") // FIXME
        histogram.recordValue(d)
        if (c == latencyN) {
          printTotal(latencyN, "ping-pong", t0, bytes.length)
          barrier.await() // this is always the last party
        }
      }.onFailure {
        case e ⇒
          e.printStackTrace
          exit(-1)
      }

    while (repeat > 0) {
      repeat -= 1
      histogram.reset()
      count.set(0)
      t0 = System.nanoTime()

      Source(1 to latencyN)
        .throttle(latencyRate, 1.second, latencyRate / 10, ThrottleMode.Shaping)
        .map { n ⇒
          if (n % (latencyN / 10) == 0)
            println(s"# send offset $n") // FIXME
          sendTimes.set(n - 1, System.nanoTime())
          payload
        }
        .runWith(new AeronSink(channel1, streamId, aeron, taskRunner))

      barrier.await()
    }

    exit(0)
  }

  def runDebugReceiver(): Unit = {
    import system.dispatcher
    Source.fromGraph(new AeronSource(channel1, streamId, aeron, taskRunner))
      .map(bytes ⇒ new String(bytes, "utf-8"))
      .runForeach { s ⇒
        println(s)
      }.onFailure {
        case e ⇒
          e.printStackTrace
          exit(-1)
      }

  }

  def runDebugSender(): Unit = {
    val fill = "0000"
    Source(1 to 1000)
      .throttle(1, 1.second, 1, ThrottleMode.Shaping)
      .map { n ⇒
        val s = (fill + n.toString).takeRight(4)
        println(s)
        s.getBytes("utf-8")
      }
      .runWith(new AeronSink(channel1, streamId, aeron, taskRunner))
  }

  def runStats(): Unit = {
    Source.tick(10.second, 10.second, "tick").runForeach { _ ⇒ stats.print(System.out) }
  }

}
