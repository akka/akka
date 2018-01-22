/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.stream.typed.scaladsl

import akka.actor.typed.scaladsl.Actor
import akka.stream.OverflowStrategy
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.event.Logging
import akka.testkit.TestKit
import akka.testkit.typed.scaladsl._
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.stream.typed.ActorMaterializer
import akka.testkit.typed.TestKitSettings
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future

object CustomGuardianAndMaterializerSpec {

  sealed trait GuardianProtocol
  case class Init(sender: ActorRef[String]) extends GuardianProtocol
  case class Msg(sender: ActorRef[String], msg: String) extends GuardianProtocol
  case object Complete extends GuardianProtocol
  case object Failed extends GuardianProtocol
}

class CustomGuardianAndMaterializerSpec extends WordSpecLike with BeforeAndAfterAll with Matchers with ScalaFutures {
  import CustomGuardianAndMaterializerSpec._
  import akka.actor.typed.scaladsl.adapter._

  // FIXME use Typed Teskit
  // The materializer creates a top-level actor when materializing a stream.
  // Currently that is not supported, because a Typed Teskit uses a typed actor system
  // with a custom guardian. Because of custom guardian, an exception is being thrown
  // when trying to create a top level actor during materialization.

  val guardian = Actor.immutable[GuardianProtocol] {
    (_, msg) ⇒ Actor.same
  }

  implicit val sys = ActorSystem(guardian, Logging.simpleName(getClass))
  implicit val testkitSettings = TestKitSettings(sys)
  implicit val mat = ActorMaterializer()(sys)

  override protected def afterAll(): Unit =
    sys.terminate()

  "ActorMaterializer" should {

    "work with typed ActorSystem with custom guardian" in {
      val it: Future[String] = Source.single("hello").runWith(Sink.head)

      it.futureValue should ===("hello")
    }

  }

}
