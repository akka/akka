package akka.io

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.dispatch.ExecutionContexts
import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.TestUtils._

import org.HdrHistogram.Histogram
import org.scalatest.matchers._

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class ByteBufferPerformanceTest extends AkkaSpec("") with ImplicitSender {
  //import system.dispatcher
  val pool = Executors.newFixedThreadPool(4)
  implicit val ec = ExecutionContexts.fromExecutor(pool)

  val testDirectCopy = true

  val testPort = 2373
  val testBufferSize = 1024 * 4
  val nSamples = 40000
  val batchNum = 50
  val addr = new InetSocketAddress("localhost", testPort)

  val testArray = Array.fill[Byte](testBufferSize)(1)
  val testWrappedByteBuffer = ByteBuffer.wrap(testArray).asReadOnlyBuffer
  val testDirectByteBuffer = {
    val bb = ByteBuffer.allocateDirect(testBufferSize)

    bb.put(testArray)
    bb.rewind()

    bb //.asReadOnlyBuffer
  }

  val directChannel: DatagramChannel = {
    val con = DatagramChannel.open()
    con.configureBlocking(false)

    con.connect(addr)

    con
  }
  val wrappedChannel: DatagramChannel = {
    val con = DatagramChannel.open()
    con.configureBlocking(false)

    con.connect(addr)

    con
  }

  def newHistogram = new Histogram(4.seconds.toMicros, 5)

  "A direct ByteBuffer (with copy) should be faster than a wrapped buffer" in {
    val failedCounter = new AtomicInteger(0)

    IO(Udp) ! Udp.Bind(self, addr)

    expectMsg(Udp.Bound(addr))

    def sendBuffer(bb: ByteBuffer, chan: DatagramChannel): Option[FiniteDuration] = {
      bb.rewind()
      val size = bb.remaining()

      val startDeadline = Deadline.now

      val sent = 0.until(batchNum).map { _ ⇒
        bb.rewind()
        if (bb.isDirect && testDirectCopy) {
          bb.put(testArray)
          bb.rewind()
        }

        chan.send(bb, addr)
      }.sum

      if (sent == (size * batchNum))
        Some(Deadline.now - startDeadline)
      else None
    }

    val directHistogram = newHistogram
    val wrappedHistogram = newHistogram

    val directFuture = Future {
      for (_ ← 0 until nSamples) {
        sendBuffer(testDirectByteBuffer, directChannel) match {
          case None ⇒
            failedCounter.incrementAndGet()
          case Some(dur) ⇒
            directHistogram.recordValue(dur.toMicros)
        }
      }
    }

    val wrappedFuture = Future {
      for (_ ← 0 until nSamples) {
        sendBuffer(testWrappedByteBuffer, wrappedChannel) match {
          case None ⇒
            failedCounter.incrementAndGet()
          case Some(dur) ⇒
            wrappedHistogram.recordValue(dur.toMicros)
        }
      }
    }

    Await.result(directFuture, 40.seconds)
    Await.result(wrappedFuture, 40.seconds)

    val dPerc = directHistogram.getHistogramData.getValueAtPercentile(_)
    def dCount(x: Double) = directHistogram.getHistogramData.getCountAtValue(dPerc(x))
    val wPerc = wrappedHistogram.getHistogramData.getValueAtPercentile(_)
    def wCount(x: Double) = wrappedHistogram.getHistogramData.getCountAtValue(wPerc(x))

    def displayPerc(p: Double) =
      info(s"$p% direct: ${dPerc(p) / batchNum.toDouble}µs (n ${dCount(p)}) " +
        s"wrapped: ${wPerc(p) / batchNum.toDouble}µs (n ${wCount(p)})")

    info(s"failed samples: ${failedCounter.get}, nSamples: $nSamples, testBufferSize: $testBufferSize, batchNum: $batchNum")
    displayPerc(99)
    displayPerc(98)
    displayPerc(80)
    displayPerc(70)
    displayPerc(50)
    displayPerc(30)
  }

  override protected def afterTermination(): Unit = {
    super.afterTermination()
    wrappedChannel.close()
    directChannel.close()
    pool.shutdownNow()
  }
}
