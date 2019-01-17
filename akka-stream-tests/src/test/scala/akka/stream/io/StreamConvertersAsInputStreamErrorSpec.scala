/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.io

import java.io.{IOException, InputStream}
import java.util.concurrent.ThreadLocalRandom

import akka.stream._
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.util.ByteString
import akka.NotUsed

/**
 * https://github.com/akka/akka/issues/25614
 * Behavior does not change if you use Supervision.stoppingDecider or not, or
 * dispatcher: akka.actor.default-dispatcher or akka.stream.default-blocking-io-dispatcher
 *
 * All combinations have been tested. Additionally, using
  * val (probe, inputStream) = TestSource.probe[ByteString].toMat(StreamConverters.asInputStream())(Keep.both).run()
 * fails too soon in the flow when attempting to on-demand raising of an exception with probe.send RuntimeException.
 */
abstract class StreamConvertersAsInputStreamErrorSpec extends StreamSpec(UnboundedMailboxConfig) {
  val decider: Supervision.Decider = Supervision.stoppingDecider
  implicit val mat = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))

  private def source: Source[ByteString, NotUsed] =
    Source(List(0, 1, 2, 3, 4))
      .map(i ⇒ if (i == 3) throw new RuntimeException("failed") else ByteString(i))


  private def readN(is: InputStream, n: Int): ByteString = {
    val buf = new Array[Byte](n)
    ThreadLocalRandom.current().nextBytes(buf)
    val r = is.read(buf)
    ByteString.fromArray(buf, 0, r)
  }

  /** Attempts to read 0 to `initialRead` and replicate the bug. This appears to be
    * a race condition related to [[akka.stream.impl.fusing.GraphInterpreter.fail]]
    * and it's use of `connection.slot`, in relation to what is returned to
    * [[akka.stream.impl.io.InputStreamAdapter.read]].
    *
    * @param initialRead attempt to read 0 to the defined bytes
    * @param andThen if defined, a second read is attempted which returns the exception.
    *                If `initialRead = 5` and no re-read is attempted, the exception is swallowed.
    * @return if `andThen` is defined, returns [[Some]] exception, otherwise [[None]]
    */
  def replicate(initialRead: Int, andThen: Option[Int]): Option[Throwable] = {
    val inputStream = source.runWith(StreamConverters.asInputStream())
    val byteString = readN(inputStream, initialRead)
    byteString shouldEqual ByteString(0, 1, 2)
    byteString.size shouldEqual 3

    val thrownOrElse = andThen.map { n => intercept[IOException](readN(inputStream, n)) }

    inputStream.close()
    thrownOrElse
  }
}

// These would not replicate if in the same test class, hence the three test classes:
class InputStreamReportsOriginalErrorSpec extends StreamConvertersAsInputStreamErrorSpec {
  "StreamConverters.asInputStream" must {
    "replicate bug: raise the original exception" in {
      val thrown = replicate(initialRead = 3, andThen = Some(2))
      thrown.forall(_.getCause.getClass == classOf[RuntimeException]) shouldBe true
    }
  }
}
class InputStreamSwallowsOriginalAndReportsTimeoutErrorSpec extends StreamConvertersAsInputStreamErrorSpec {
  "StreamConverters.asInputStream" must {
    "replicate bug: by reading some, then reading more, swallows original failure and reports as timeout" in {
      // on second read attempt, hangs, read() receives null, throws IOException: Timeout
      // if you give connection.slot a millisecond after `connection.slot = Failed(ex, connection.slot)`
      // there is no hang and the original exception is reported
      val thrown = replicate(initialRead = 4, andThen = Some(2))
      thrown.forall(_.getMessage == "Timeout on waiting for new data") shouldBe true
    }
  }
}
class InputStreamSwallowsOriginalErrorSpec extends StreamConvertersAsInputStreamErrorSpec {
  "StreamConverters.asInputStream" must {
    "replicate bug: try to read all, original exception falls into black hole" in {
      // if you attempt to read 5 on the first read and nothing after that, it swallows the exception.
      // if you attempt to read again, same outcome as initialRead=4
      replicate(initialRead = 5, andThen = None) shouldBe None
    }
  }
}
