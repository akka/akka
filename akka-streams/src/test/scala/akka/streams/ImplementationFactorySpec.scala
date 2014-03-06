package akka.streams

import org.scalatest.{ FreeSpec, BeforeAndAfterAll, ShouldMatchers }
import akka.testkit.TestKitBase

abstract class StreamGeneratorSpec extends FreeSpec with TestKitBase with ShouldMatchers with BeforeAndAfterAll {
  implicit def factory: StreamGenerator = factoryWithFanOutBuffer(1)
  def factoryWithFanOutBuffer(capacity: Int): StreamGenerator

  override protected def afterAll(): Unit = system.shutdown()
}

