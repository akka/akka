/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ RunnableGraph, Flow, Sink, Source }
import akka.stream.testkit._
import org.reactivestreams.Processor

class ReactiveStreamsDocSpec extends AkkaSpec {
  import TwitterStreamQuickstartDocSpec._

  implicit val mat = ActorMaterializer()

  //#imports
  import org.reactivestreams.Publisher
  import org.reactivestreams.Subscriber
  //#imports

  trait Fixture {
    //#authors
    val authors = Flow[Tweet]
      .filter(_.hashtags.contains(akka))
      .map(_.author)

    //#authors

    //#tweets-publisher
    def tweets: Publisher[Tweet]
    //#tweets-publisher

    //#author-storage-subscriber
    def storage: Subscriber[Author]
    //#author-storage-subscriber

    //#author-alert-subscriber
    def alert: Subscriber[Author]
    //#author-alert-subscriber
  }

  val impl = new Fixture {
    override def tweets: Publisher[Tweet] =
      TwitterStreamQuickstartDocSpec.tweets.runWith(Sink.publisher(false))

    override def storage = TestSubscriber.manualProbe[Author]

    override def alert = TestSubscriber.manualProbe[Author]
  }

  def assertResult(storage: TestSubscriber.ManualProbe[Author]): Unit = {
    val sub = storage.expectSubscription()
    sub.request(10)
    storage.expectNext(Author("rolandkuhn"))
    storage.expectNext(Author("patriknw"))
    storage.expectNext(Author("bantonsson"))
    storage.expectNext(Author("drewhk"))
    storage.expectNext(Author("ktosopl"))
    storage.expectNext(Author("mmartynas"))
    storage.expectNext(Author("akkateam"))
    storage.expectComplete()
  }

  "reactive streams publisher via flow to subscriber" in {
    import impl._
    val storage = impl.storage

    //#connect-all
    Source(tweets).via(authors).to(Sink(storage)).run()
    //#connect-all

    assertResult(storage)
  }

  "flow as publisher and subscriber" in {
    import impl._
    val storage = impl.storage

    //#flow-publisher-subscriber
    val processor: Processor[Tweet, Author] = authors.toProcessor.run()

    tweets.subscribe(processor)
    processor.subscribe(storage)
    //#flow-publisher-subscriber

    assertResult(storage)
  }

  "source as publisher" in {
    import impl._
    val storage = impl.storage

    //#source-publisher
    val authorPublisher: Publisher[Author] =
      Source(tweets).via(authors).runWith(Sink.publisher(fanout = false))

    authorPublisher.subscribe(storage)
    //#source-publisher

    assertResult(storage)
  }

  "source as fanoutPublisher" in {
    import impl._
    val storage = impl.storage
    val alert = impl.alert

    //#source-fanoutPublisher
    val authorPublisher: Publisher[Author] =
      Source(tweets).via(authors)
        .runWith(Sink.publisher(fanout = true))

    authorPublisher.subscribe(storage)
    authorPublisher.subscribe(alert)
    //#source-fanoutPublisher

    // this relies on fanoutPublisher buffer size > number of authors
    assertResult(storage)
    assertResult(alert)
  }

  "sink as subscriber" in {
    import impl._
    val storage = impl.storage

    //#sink-subscriber
    val tweetSubscriber: Subscriber[Tweet] =
      authors.to(Sink(storage)).runWith(Source.subscriber[Tweet])

    tweets.subscribe(tweetSubscriber)
    //#sink-subscriber

    assertResult(storage)
  }

  "use a processor" in {

    //#use-processor
    // An example Processor factory
    def createProcessor: Processor[Int, Int] = Flow[Int].toProcessor.run()

    val flow: Flow[Int, Int, Unit] = Flow.fromProcessor(() => createProcessor)
    //#use-processor

  }

}
