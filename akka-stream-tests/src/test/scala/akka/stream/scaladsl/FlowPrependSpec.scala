/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.testkit.AkkaSpec
import com.github.ghik.silencer.silent

@silent // for keeping imports
class FlowPrependSpec extends AkkaSpec {

//#prepend
  import akka.stream.scaladsl.Source
  import akka.stream.scaladsl.Sink

//#prepend

  val settings = ActorMaterializerSettings(system)

  implicit val materializer = ActorMaterializer(settings)

  "An Prepend flow" should {

    "work in entrance example" in {
      //#prepend
      val ladies = Source(List("Emma", "Emily"))
      val gentlemen = Source(List("Liam", "William"))

      gentlemen.prepend(ladies).runWith(Sink.foreach(println))
      // this will print "Emma", "Emily", "Liam", "William"
      //#prepend
    }
  }
}
