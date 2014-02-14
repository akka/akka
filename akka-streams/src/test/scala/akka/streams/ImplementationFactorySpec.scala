package akka.streams

import org.scalatest.{ FreeSpec, BeforeAndAfterAll, ShouldMatchers }
import akka.testkit.TestKitBase

abstract class ImplementationFactorySpec extends FreeSpec with TestKitBase with ShouldMatchers with BeforeAndAfterAll {
  implicit def factory: ImplementationFactory

  override protected def afterAll(): Unit = system.shutdown()
}

