/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream.cookbook

import akka.NotUsed
import akka.stream.scaladsl._

import scala.collection.immutable
import scala.concurrent.Future

class RecipeSourceFromFunction extends RecipeSpec {

  "A source that repeatedly evaluates a function" must {

    "be a mapping of Source.repeat" in {
      var counter = 0
      def builderFunction(): Int = {
        counter += 1
        counter
      }
      //#source-from-function
      val source = Source.repeat(NotUsed).map(_ ⇒ builderFunction())
      //#source-from-function
      val f: Future[immutable.Seq[Int]] = source.take(3).runWith(Sink.seq)
      f.futureValue should ===(Seq(1, 2, 3))
    }
  }
}
