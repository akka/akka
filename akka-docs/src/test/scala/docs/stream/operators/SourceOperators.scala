/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators

object SourceOperators {

  def fromFuture = {
    //#sourceFromFuture

    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.{ Done, NotUsed }
    import akka.stream.scaladsl._

    import scala.concurrent.Future

    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val source: Source[Int, NotUsed] = Source.fromFuture(Future.successful(10))
    val sink: Sink[Int, Future[Done]] = Sink.foreach((i: Int) ⇒ println(i))

    val done: Future[Done] = source.runWith(sink) //10
    //#sourceFromFuture
  }
}
