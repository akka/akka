/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.cookbook

import akka.stream.scaladsl._

class RecipeLastElements extends RecipeSpec {

  "Recipe for collecting the last n elements" must {

    "work" in {

      //#last-elements
      val f = Source(List(1, 2, 3, 4, 5))
        .runWith(Sink.fold(Vector.empty[Int]) { (acc, elem) ⇒ (acc :+ elem).takeRight(3) })
      //#last-elements

      f.futureValue.distinct shouldBe Vector(3, 4, 5)
    }

  }

}
