/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.Keep

object BroadcastDocExample {

  implicit val system: ActorSystem = ???

  //#broadcast
  import akka.NotUsed
  import akka.stream.ClosedShape
  import akka.stream.scaladsl.Flow
  import akka.stream.scaladsl.GraphDSL
  import akka.stream.scaladsl.RunnableGraph
  import akka.stream.scaladsl.Sink
  import akka.stream.scaladsl.Source

  val source: Source[Int, NotUsed] =
    Source.fromIterator(() => Iterator.continually(ThreadLocalRandom.current().nextInt(100))).take(100)

  val countSink: Sink[Int, Future[Int]] = Flow[Int].toMat(Sink.fold(0)((acc, elem) => acc + 1))(Keep.right)
  val minSink: Sink[Int, Future[Int]] = Flow[Int].toMat(Sink.fold(0)((acc, elem) => math.min(acc, elem)))(Keep.right)
  val maxSink: Sink[Int, Future[Int]] = Flow[Int].toMat(Sink.fold(0)((acc, elem) => math.max(acc, elem)))(Keep.right)

  val (count: Future[Int], min: Future[Int], max: Future[Int]) =
    RunnableGraph
      .fromGraph(GraphDSL.create(countSink, minSink, maxSink)(Tuple3.apply) {
        implicit builder => (countS, minS, maxS) =>
          import GraphDSL.Implicits._
          val broadcast = builder.add(Broadcast[Int](3))
          source ~> broadcast
          broadcast.out(0) ~> countS
          broadcast.out(0) ~> minS
          broadcast.out(0) ~> maxS
          ClosedShape
      })
      .run()
  //#broadcast

  //#broadcast-async
  RunnableGraph.fromGraph(GraphDSL.create(countSink, minSink, maxSink)(Tuple3.apply) {
    implicit builder => (countS, minS, maxS) =>
      import GraphDSL.Implicits._
      val broadcast = builder.add(Broadcast[Int](3))
      source ~> broadcast
      broadcast.out(0) ~> Flow[Int].async ~> countS
      broadcast.out(0) ~> Flow[Int].async ~> minS
      broadcast.out(0) ~> Flow[Int].async ~> maxS
      ClosedShape
  })
  //#broadcast-async
}
