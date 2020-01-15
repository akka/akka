/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.StreamSpec

import org.scalatest.concurrent.ScalaFutures

class SystemMaterializerEagerStartupSpec extends StreamSpec with ScalaFutures {

  "The SystemMaterializer" must {

    "be eagerly started on system startup" in {
      system.hasExtension(SystemMaterializer.lookup) should ===(false)
    }

    "provide a materializer when invoked" in {
      implicit val mat: Materializer = SystemMaterializer(system).materializer
      Source.single("asdf")
        .runWith(Sink.seq)
        .futureValue should be(Seq("asdf"))
    }
  }

}
