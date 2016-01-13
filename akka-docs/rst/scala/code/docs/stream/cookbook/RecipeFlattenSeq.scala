package docs.stream.cookbook

import akka.stream.scaladsl.{ Sink, Source }

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

class RecipeFlattenSeq extends RecipeSpec {

  "Recipe for flatteing a stream of seqs" must {

    "work" in {

      val someDataSource = Source(List(List("1"), List("2"), List("3", "4", "5"), List("6", "7")))

      //#flattening-seqs
      val myData: Source[List[Message], Unit] = someDataSource
      val flattened: Source[Message, Unit] = myData.mapConcat(identity)
      //#flattening-seqs

      Await.result(flattened.grouped(8).runWith(Sink.head), 3.seconds) should be(List("1", "2", "3", "4", "5", "6", "7"))

    }

  }

}
