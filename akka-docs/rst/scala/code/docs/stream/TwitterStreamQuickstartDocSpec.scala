/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

//#imports

import akka.actor.ActorSystem
import akka.stream.{ ClosedShape, ActorMaterializer, OverflowStrategy }
import akka.stream.scaladsl._

import scala.concurrent.Await
import scala.concurrent.Future

//#imports

import akka.stream.testkit.AkkaSpec

object TwitterStreamQuickstartDocSpec {
  //#model
  final case class Author(handle: String)

  final case class Hashtag(name: String)

  final case class Tweet(author: Author, timestamp: Long, body: String) {
    def hashtags: Set[Hashtag] =
      body.split(" ").collect { case t if t.startsWith("#") => Hashtag(t) }.toSet
  }

  val akka = Hashtag("#akka")
  //#model

  val tweets = Source(
    Tweet(Author("rolandkuhn"), System.currentTimeMillis, "#akka rocks!") ::
      Tweet(Author("patriknw"), System.currentTimeMillis, "#akka !") ::
      Tweet(Author("bantonsson"), System.currentTimeMillis, "#akka !") ::
      Tweet(Author("drewhk"), System.currentTimeMillis, "#akka !") ::
      Tweet(Author("ktosopl"), System.currentTimeMillis, "#akka on the rocks!") ::
      Tweet(Author("mmartynas"), System.currentTimeMillis, "wow #akka !") ::
      Tweet(Author("akkateam"), System.currentTimeMillis, "#akka rocks!") ::
      Tweet(Author("bananaman"), System.currentTimeMillis, "#bananas rock!") ::
      Tweet(Author("appleman"), System.currentTimeMillis, "#apples rock!") ::
      Tweet(Author("drama"), System.currentTimeMillis, "we compared #apples to #oranges!") ::
      Nil)
}

class TwitterStreamQuickstartDocSpec extends AkkaSpec {
  import TwitterStreamQuickstartDocSpec._

  implicit val executionContext = system.dispatcher

  // Disable println
  def println(s: Any): Unit = ()

  trait Example0 {
    //#tweet-source
    val tweets: Source[Tweet, Unit]
    //#tweet-source
  }

  trait Example1 {
    //#first-sample
    //#materializer-setup
    implicit val system = ActorSystem("reactive-tweets")
    implicit val materializer = ActorMaterializer()
    //#materializer-setup
    //#first-sample
  }

  implicit val materializer = ActorMaterializer()

  "filter and map" in {
    //#first-sample

    //#authors-filter-map
    val authors: Source[Author, Unit] =
      tweets
        .filter(_.hashtags.contains(akka))
        .map(_.author)
    //#first-sample
    //#authors-filter-map

    trait Example3 {
      //#authors-collect
      val authors: Source[Author, Unit] =
        tweets.collect { case t if t.hashtags.contains(akka) => t.author }
      //#authors-collect
    }

    //#first-sample

    //#authors-foreachsink-println
    authors.runWith(Sink.foreach(println))
    //#authors-foreachsink-println
    //#first-sample

    //#authors-foreach-println
    authors.runForeach(println)
    //#authors-foreach-println
  }

  "mapConcat hashtags" in {
    //#hashtags-mapConcat
    val hashtags: Source[Hashtag, Unit] = tweets.mapConcat(_.hashtags.toList)
    //#hashtags-mapConcat
  }

  trait HiddenDefinitions {
    //#flow-graph-broadcast
    val writeAuthors: Sink[Author, Unit] = ???
    val writeHashtags: Sink[Hashtag, Unit] = ???
    //#flow-graph-broadcast
  }

  "simple broadcast" in {
    val writeAuthors: Sink[Author, Future[Unit]] = Sink.ignore
    val writeHashtags: Sink[Hashtag, Future[Unit]] = Sink.ignore

    // format: OFF
    //#flow-graph-broadcast
    val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val bcast = b.add(Broadcast[Tweet](2))
      tweets ~> bcast.in
      bcast.out(0) ~> Flow[Tweet].map(_.author) ~> writeAuthors 
      bcast.out(1) ~> Flow[Tweet].mapConcat(_.hashtags.toList) ~> writeHashtags
      ClosedShape
    })
    g.run()
    //#flow-graph-broadcast
    // format: ON
  }

  "slowProcessing" in {
    def slowComputation(t: Tweet): Long = {
      Thread.sleep(500) // act as if performing some heavy computation
      42
    }

    //#tweets-slow-consumption-dropHead
    tweets
      .buffer(10, OverflowStrategy.dropHead)
      .map(slowComputation)
      .runWith(Sink.ignore)
    //#tweets-slow-consumption-dropHead
  }

  "backpressure by readline" in {
    trait X {
      import scala.concurrent.duration._

      //#backpressure-by-readline
      val completion: Future[Unit] =
        Source(1 to 10)
          .map(i => { println(s"map => $i"); i })
          .runForeach { i => readLine(s"Element = $i; continue reading? [press enter]\n") }

      Await.ready(completion, 1.minute)
      //#backpressure-by-readline
    }
  }

  "count elements on finite stream" in {
    //#tweets-fold-count
    val count: Flow[Tweet, Int, Unit] = Flow[Tweet].map(_ => 1)

    val sumSink: Sink[Int, Future[Int]] = Sink.fold[Int, Int](0)(_ + _)

    val counterGraph: RunnableGraph[Future[Int]] =
      tweets
        .via(count)
        .toMat(sumSink)(Keep.right)

    val sum: Future[Int] = counterGraph.run()

    sum.foreach(c => println(s"Total tweets processed: $c"))
    //#tweets-fold-count

    new AnyRef {
      //#tweets-fold-count-oneline
      val sum: Future[Int] = tweets.map(t => 1).runWith(sumSink)
      //#tweets-fold-count-oneline
    }
  }

  "materialize multiple times" in {
    val tweetsInMinuteFromNow = tweets // not really in second, just acting as if

    //#tweets-runnable-flow-materialized-twice
    val sumSink = Sink.fold[Int, Int](0)(_ + _)
    val counterRunnableGraph: RunnableGraph[Future[Int]] =
      tweetsInMinuteFromNow
        .filter(_.hashtags contains akka)
        .map(t => 1)
        .toMat(sumSink)(Keep.right)

    // materialize the stream once in the morning
    val morningTweetsCount: Future[Int] = counterRunnableGraph.run()
    // and once in the evening, reusing the flow
    val eveningTweetsCount: Future[Int] = counterRunnableGraph.run()

    //#tweets-runnable-flow-materialized-twice

    val sum: Future[Int] = counterRunnableGraph.run()

    sum.map { c => println(s"Total tweets processed: $c") }
  }

}
