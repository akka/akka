/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream

//#imports

import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.stream.{ ClosedShape, ActorMaterializer, OverflowStrategy }
import akka.stream.scaladsl._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.io.StdIn.readLine

//#imports

import akka.testkit.AkkaSpec

object TwitterStreamQuickstartDocSpec {
  //#fiddle_code
  import akka.NotUsed
  import akka.actor.ActorSystem
  import akka.stream.ActorMaterializer
  import akka.stream.scaladsl._

  //#model
  final case class Author(handle: String)

  final case class Hashtag(name: String)

  final case class Tweet(author: Author, timestamp: Long, body: String) {
    def hashtags: Set[Hashtag] = body.split(" ").collect {
      case t if t.startsWith("#") ⇒ Hashtag(t.replaceAll("[^#\\w]", ""))
    }.toSet
  }

  val akkaTag = Hashtag("#akka")
  //#model

  //#fiddle_code

  abstract class TweetSourceDecl {
    //#tweet-source
    val tweets: Source[Tweet, NotUsed]
    //#tweet-source
  }

  //#fiddle_code
  val tweets: Source[Tweet, NotUsed] = Source(
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

  //#fiddle_code
}

class TwitterStreamQuickstartDocSpec extends AkkaSpec {
  import TwitterStreamQuickstartDocSpec._

  implicit val executionContext = system.dispatcher

  // Disable println
  def println(s: Any): Unit = ()

  trait Example1 {
    //#fiddle_code
    //#first-sample
    //#materializer-setup
    implicit val system = ActorSystem("reactive-tweets")
    implicit val materializer = ActorMaterializer()
    //#materializer-setup
    //#first-sample

    //#fiddle_code
  }

  implicit val materializer = ActorMaterializer()

  "filter and map" in {
    //#first-sample

    //#authors-filter-map
    val authors: Source[Author, NotUsed] =
      tweets
        .filter(_.hashtags.contains(akkaTag))
        .map(_.author)
    //#first-sample
    //#authors-filter-map

    trait Example3 {
      //#authors-collect
      val authors: Source[Author, NotUsed] =
        tweets.collect { case t if t.hashtags.contains(akkaTag) ⇒ t.author }
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
    val hashtags: Source[Hashtag, NotUsed] = tweets.mapConcat(_.hashtags.toList)
    //#hashtags-mapConcat
  }

  trait HiddenDefinitions {
    //#graph-dsl-broadcast
    val writeAuthors: Sink[Author, NotUsed] = ???
    val writeHashtags: Sink[Hashtag, NotUsed] = ???
    //#graph-dsl-broadcast
  }

  "simple broadcast" in {
    val writeAuthors: Sink[Author, Future[Done]] = Sink.ignore
    val writeHashtags: Sink[Hashtag, Future[Done]] = Sink.ignore

    // format: OFF
    //#graph-dsl-broadcast
    val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val bcast = b.add(Broadcast[Tweet](2))
      tweets ~> bcast.in
      bcast.out(0) ~> Flow[Tweet].map(_.author) ~> writeAuthors
      bcast.out(1) ~> Flow[Tweet].mapConcat(_.hashtags.toList) ~> writeHashtags
      ClosedShape
    })
    g.run()
    //#graph-dsl-broadcast
    // format: ON
  }

  "simple fiddle showcase" in {

    //#fiddle_code
    tweets
      .filterNot(_.hashtags.contains(akkaTag)) // Remove all tweets containing #akka hashtag
      .map(_.hashtags) // Get all sets of hashtags ...
      .reduce(_ ++ _) // ... and reduce them to a single set, removing duplicates across all tweets
      .mapConcat(identity) // Flatten the set of hashtags to a stream of hashtags
      .map(_.name.toUpperCase) // Convert all hashtags to upper case
      .runWith(Sink.foreach(println)) // Attach the Flow to a Sink that will finally print the hashtags

      //#fiddle_code
      .value
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
      val completion: Future[Done] =
        Source(1 to 10)
          .map(i ⇒ { println(s"map => $i"); i })
          .runForeach { i ⇒ readLine(s"Element = $i; continue reading? [press enter]\n") }

      Await.ready(completion, 1.minute)
      //#backpressure-by-readline
    }
  }

  "count elements on finite stream" in {
    //#tweets-fold-count
    val count: Flow[Tweet, Int, NotUsed] = Flow[Tweet].map(_ ⇒ 1)

    val sumSink: Sink[Int, Future[Int]] = Sink.fold[Int, Int](0)(_ + _)

    val counterGraph: RunnableGraph[Future[Int]] =
      tweets
        .via(count)
        .toMat(sumSink)(Keep.right)

    val sum: Future[Int] = counterGraph.run()

    sum.foreach(c ⇒ println(s"Total tweets processed: $c"))
    //#tweets-fold-count

    new AnyRef {
      //#tweets-fold-count-oneline
      val sum: Future[Int] = tweets.map(t ⇒ 1).runWith(sumSink)
      //#tweets-fold-count-oneline
    }
  }

  "materialize multiple times" in {
    val tweetsInMinuteFromNow = tweets // not really in second, just acting as if

    //#tweets-runnable-flow-materialized-twice
    val sumSink = Sink.fold[Int, Int](0)(_ + _)
    val counterRunnableGraph: RunnableGraph[Future[Int]] =
      tweetsInMinuteFromNow
        .filter(_.hashtags contains akkaTag)
        .map(t ⇒ 1)
        .toMat(sumSink)(Keep.right)

    // materialize the stream once in the morning
    val morningTweetsCount: Future[Int] = counterRunnableGraph.run()
    // and once in the evening, reusing the flow
    val eveningTweetsCount: Future[Int] = counterRunnableGraph.run()

    //#tweets-runnable-flow-materialized-twice

    val sum: Future[Int] = counterRunnableGraph.run()

    sum.map { c ⇒ println(s"Total tweets processed: $c") }
  }

}
