/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

object SourceOperators {

  def fromFuture = {
    //#sourceFromFuture

    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._
    import akka.{ Done, NotUsed }

    import scala.concurrent.Future

    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val source: Source[Int, NotUsed] = Source.fromFuture(Future.successful(10))
    val sink: Sink[Int, Future[Done]] = Sink.foreach((i: Int) => println(i))

    val done: Future[Done] = source.runWith(sink) //10
    //#sourceFromFuture
  }

  def actorRef(): Unit = {
    //#actorRef

    import akka.actor.Status.Success
    import akka.actor.ActorRef
    import akka.stream.OverflowStrategy
    import akka.stream.scaladsl._

    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    val bufferSize = 100

    val source: Source[Any, ActorRef] = Source.actorRef[Any](bufferSize, OverflowStrategy.dropHead)
    val actorRef: ActorRef = source.to(Sink.foreach(println)).run()

    actorRef ! "hello"
    actorRef ! "hello"

    // The stream completes successfully with the following message
    actorRef ! Success("completes stream")
    //#actorRef
  }
}
