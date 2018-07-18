/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.{Done, NotUsed}
import akka.stream.scaladsl._

import scala.concurrent.Future

object SourceFromFuture {
  //#sourceFromFuture

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  val source: Source[Int, NotUsed] = Source.fromFuture(Future.successful(10))
  val sink: Sink[Int, Future[Done]] = Sink.foreach((a: Int) ⇒ println(a))

  val done: Future[Done] = source.runWith(sink) //10
  //#sourceFromFuture
}
