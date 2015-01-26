/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

//#imports

import akka.actor.ActorSystem
import akka.stream.FlowMaterializer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.FlowGraph
import akka.stream.scaladsl.FlowGraphImplicits
import akka.stream.scaladsl.MaterializedMap
import akka.stream.scaladsl.RunnableFlow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

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

  trait Example0 {
    //#tweet-source
    val tweets: Source[Tweet]
    //#tweet-source
  }

  trait Example1 {
    //#materializer-setup
    implicit val system = ActorSystem("reactive-tweets")
    implicit val mat = FlowMaterializer()
    //#materializer-setup
  }

  implicit val mat = FlowMaterializer()

  "filter and map" in {
    //#authors-filter-map
    val authors: Source[Author] =
      tweets
        .filter(_.hashtags.contains(akka))
        .map(_.author)
    //#authors-filter-map

    trait Example3 {
      //#authors-collect
      val authors: Source[Author] =
        tweets.collect { case t if t.hashtags.contains(akka) => t.author }
      //#authors-collect
    }

    //#authors-foreachsink-println
    authors.runWith(Sink.foreach(println))
    //#authors-foreachsink-println

    //#authors-foreach-println
    authors.runForeach(println)
    //#authors-foreach-println
  }

  "mapConcat hashtags" in {
    //#hashtags-mapConcat
    val hashtags: Source[Hashtag] = tweets.mapConcat(_.hashtags.toList)
    //#hashtags-mapConcat
  }

  trait HiddenDefinitions {
    //#flow-graph-broadcast
    val writeAuthors: Sink[Author] = ???
    val writeHashtags: Sink[Hashtag] = ???
    //#flow-graph-broadcast
  }

  "simple broadcast" in {
    val writeAuthors: Sink[Author] = Sink.ignore
    val writeHashtags: Sink[Hashtag] = Sink.ignore

    // format: OFF
    //#flow-graph-broadcast
    val g = FlowGraph { implicit builder =>
      import FlowGraphImplicits._

      val b = Broadcast[Tweet]
      tweets ~> b ~> Flow[Tweet].map(_.author) ~> writeAuthors
                b ~> Flow[Tweet].mapConcat(_.hashtags.toList) ~> writeHashtags
    }
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
    val sumSink = Sink.fold[Int, Int](0)(_ + _)

    val counter: RunnableFlow = tweets.map(t => 1).to(sumSink)
    val map: MaterializedMap = counter.run()

    val sum: Future[Int] = map.get(sumSink)

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
    val counterRunnableFlow: RunnableFlow =
      tweetsInMinuteFromNow
        .filter(_.hashtags contains akka)
        .map(t => 1)
        .to(sumSink)

    // materialize the stream once in the morning
    val morningMaterialized = counterRunnableFlow.run()
    // and once in the evening, reusing the
    val eveningMaterialized = counterRunnableFlow.run()

    // the sumSink materialized two different futures
    // we use it as key to get the materialized value out of the materialized map
    val morningTweetsCount: Future[Int] = morningMaterialized.get(sumSink)
    val eveningTweetsCount: Future[Int] = eveningMaterialized.get(sumSink)
    //#tweets-runnable-flow-materialized-twice

    val map: MaterializedMap = counterRunnableFlow.run()

    val sum: Future[Int] = map.get(sumSink)

    sum.map { c => println(s"Total tweets processed: $c") }
  }

}
