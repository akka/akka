/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.io.InputStream

import akka.stream.scaladsl.Sink
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings }
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

class InputStreamSourceSpec extends AkkaSpec(UnboundedMailboxConfig) with ScalaFutures {

  val settings = ActorFlowMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorFlowMaterializer(settings)

  "InputStreamSource" must {
    "read bytes from InputStream" in assertAllStagesStopped {
      val f = InputStreamSource(() ⇒ new InputStream {
        @volatile var buf = List("a", "b", "c").map(_.charAt(0).toInt)
        override def read(): Int = {
          buf match {
            case head :: tail ⇒
              buf = tail
              head
            case Nil ⇒
              -1
          }

        }
      })
        .runWith(Sink.head)

      f.futureValue should ===(ByteString("abc"))
    }
  }

}
