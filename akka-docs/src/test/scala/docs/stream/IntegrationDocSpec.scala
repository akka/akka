/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream

import akka.NotUsed

import scala.concurrent.duration._
import akka.testkit.AkkaSpec
import akka.stream.scaladsl._
import akka.stream._

import scala.concurrent.Future
import akka.testkit.TestProbe
import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Status }
import com.typesafe.config.ConfigFactory
import akka.util.Timeout

import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicInteger

import akka.stream.scaladsl.Flow
import akka.Done
import akka.actor.Status.Status
import akka.stream.QueueOfferResult.{ Dropped, Enqueued }

object IntegrationDocSpec {
  import TwitterStreamQuickstartDocSpec._

  val config = ConfigFactory.parseString("""
    #//#blocking-dispatcher-config
    blocking-dispatcher {
      executor = "thread-pool-executor"
      thread-pool-executor {
        core-pool-size-min    = 10
        core-pool-size-max    = 10
      }
    }
    #//#blocking-dispatcher-config

    akka.actor.default-mailbox.mailbox-type = akka.dispatch.UnboundedMailbox
    """)

  class AddressSystem {
    //#email-address-lookup
    def lookupEmail(handle: String): Future[Option[String]] =
      //#email-address-lookup
      Future.successful(Some(handle + "@somewhere.com"))

    //#phone-lookup
    def lookupPhoneNumber(handle: String): Future[Option[String]] =
      //#phone-lookup
      Future.successful(Some(handle.hashCode.toString))
  }

  class AddressSystem2 {
    //#email-address-lookup2
    def lookupEmail(handle: String): Future[String] =
      //#email-address-lookup2
      Future.successful(handle + "@somewhere.com")
  }

  final case class Email(to: String, title: String, body: String)
  final case class TextMessage(to: String, body: String)

  class EmailServer(probe: ActorRef) {
    //#email-server-send
    def send(email: Email): Future[Unit] = {
      // ...
      //#email-server-send
      probe ! email.to
      Future.successful(())
      //#email-server-send
    }
    //#email-server-send
  }

  class SmsServer(probe: ActorRef) {
    //#sms-server-send
    def send(text: TextMessage): Unit = {
      // ...
      //#sms-server-send
      probe ! text.to
      //#sms-server-send
    }
    //#sms-server-send
  }

  final case class Save(tweet: Tweet)
  final case object SaveDone

  class DatabaseService(probe: ActorRef) extends Actor {
    override def receive = {
      case Save(tweet: Tweet) ⇒
        probe ! tweet.author.handle
        sender() ! SaveDone
    }
  }

  //#sometimes-slow-service
  class SometimesSlowService(implicit ec: ExecutionContext) {
    //#sometimes-slow-service
    def println(s: String): Unit = ()
    //#sometimes-slow-service

    private val runningCount = new AtomicInteger

    def convert(s: String): Future[String] = {
      println(s"running: $s (${runningCount.incrementAndGet()})")
      Future {
        if (s.nonEmpty && s.head.isLower)
          Thread.sleep(500)
        else
          Thread.sleep(20)
        println(s"completed: $s (${runningCount.decrementAndGet()})")
        s.toUpperCase
      }
    }
  }
  //#sometimes-slow-service

  //#ask-actor
  class Translator extends Actor {
    def receive = {
      case word: String ⇒
        // ... process message
        val reply = word.toUpperCase
        sender() ! reply // reply to the ask
    }
  }
  //#ask-actor

}

class IntegrationDocSpec extends AkkaSpec(IntegrationDocSpec.config) {
  import TwitterStreamQuickstartDocSpec._
  import IntegrationDocSpec._

  implicit val materializer = ActorMaterializer()
  val ref: ActorRef = system.actorOf(Props[Translator])

  "ask" in {
    //#ask
    implicit val askTimeout = Timeout(5.seconds)
    val words: Source[String, NotUsed] =
      Source(List("hello", "hi"))

    words
      .ask[String](parallelism = 5)(ref)
      // continue processing of the replies from the actor
      .map(_.toLowerCase)
      .runWith(Sink.ignore)
    //#ask
  }

  "calling external service with mapAsync" in {
    val probe = TestProbe()
    val addressSystem = new AddressSystem
    val emailServer = new EmailServer(probe.ref)

    //#tweet-authors
    val authors: Source[Author, NotUsed] =
      tweets
        .filter(_.hashtags.contains(akkaTag))
        .map(_.author)
    //#tweet-authors

    //#email-addresses-mapAsync
    val emailAddresses: Source[String, NotUsed] =
      authors
        .mapAsync(4)(author ⇒ addressSystem.lookupEmail(author.handle))
        .collect { case Some(emailAddress) ⇒ emailAddress }
    //#email-addresses-mapAsync

    //#send-emails
    val sendEmails: RunnableGraph[NotUsed] =
      emailAddresses
        .mapAsync(4)(address ⇒ {
          emailServer.send(
            Email(to = address, title = "Akka", body = "I like your tweet"))
        })
        .to(Sink.ignore)

    sendEmails.run()
    //#send-emails

    probe.expectMsg("rolandkuhn@somewhere.com")
    probe.expectMsg("patriknw@somewhere.com")
    probe.expectMsg("bantonsson@somewhere.com")
    probe.expectMsg("drewhk@somewhere.com")
    probe.expectMsg("ktosopl@somewhere.com")
    probe.expectMsg("mmartynas@somewhere.com")
    probe.expectMsg("akkateam@somewhere.com")
  }

  "actorRefWithAck" in {
    //#actorRefWithAck
    val words: Source[String, NotUsed] =
      Source(List("hello", "hi"))

    // sent from actor to stream to "ack" processing of given element
    val AckMessage = AckingReceiver.Ack

    // sent from stream to actor to indicate start, end or failure of stream:
    val InitMessage = AckingReceiver.StreamInitialized
    val OnCompleteMessage = AckingReceiver.StreamCompleted
    val onErrorMessage = (ex: Throwable) ⇒ AckingReceiver.StreamFailure(ex)

    val probe = TestProbe()
    val receiver = system.actorOf(
      Props(new AckingReceiver(probe.ref, ackWith = AckMessage)))
    val sink = Sink.actorRefWithAck(
      receiver,
      onInitMessage = InitMessage,
      ackMessage = AckMessage,
      onCompleteMessage = OnCompleteMessage,
      onFailureMessage = onErrorMessage
    )

    words
      .map(_.toLowerCase)
      .runWith(sink)

    probe.expectMsg("Stream initialized!")
    probe.expectMsg("hello")
    probe.expectMsg("hi")
    probe.expectMsg("Stream completed!")
    //#actorRefWithAck
  }

  //#actorRefWithAck-actor
  object AckingReceiver {
    case object Ack

    case object StreamInitialized
    case object StreamCompleted
    final case class StreamFailure(ex: Throwable)
  }

  class AckingReceiver(probe: ActorRef, ackWith: Any) extends Actor with ActorLogging {
    import AckingReceiver._

    def receive: Receive = {
      case StreamInitialized ⇒
        log.info("Stream initialized!")
        probe ! "Stream initialized!"
        sender() ! Ack // ack to allow the stream to proceed sending more elements

      case el: String ⇒
        log.info("Received element: {}", el)
        probe ! el
        sender() ! Ack // ack to allow the stream to proceed sending more elements

      case StreamCompleted ⇒
        log.info("Stream completed!")
        probe ! "Stream completed!"
      case StreamFailure(ex) ⇒
        log.error(ex, "Stream failed!")
    }
  }
  //#actorRefWithAck-actor

  "lookup email with mapAsync and supervision" in {
    val addressSystem = new AddressSystem2
    val authors: Source[Author, NotUsed] =
      tweets.filter(_.hashtags.contains(akkaTag)).map(_.author)

    //#email-addresses-mapAsync-supervision
    import ActorAttributes.supervisionStrategy
    import Supervision.resumingDecider

    val emailAddresses: Source[String, NotUsed] =
      authors.via(
        Flow[Author].mapAsync(4)(author ⇒ addressSystem.lookupEmail(author.handle))
          .withAttributes(supervisionStrategy(resumingDecider)))
    //#email-addresses-mapAsync-supervision
  }

  "calling external service with mapAsyncUnordered" in {
    val probe = TestProbe()
    val addressSystem = new AddressSystem
    val emailServer = new EmailServer(probe.ref)

    //#external-service-mapAsyncUnordered
    val authors: Source[Author, NotUsed] =
      tweets.filter(_.hashtags.contains(akkaTag)).map(_.author)

    val emailAddresses: Source[String, NotUsed] =
      authors
        .mapAsyncUnordered(4)(author ⇒ addressSystem.lookupEmail(author.handle))
        .collect { case Some(emailAddress) ⇒ emailAddress }

    val sendEmails: RunnableGraph[NotUsed] =
      emailAddresses
        .mapAsyncUnordered(4)(address ⇒ {
          emailServer.send(
            Email(to = address, title = "Akka", body = "I like your tweet"))
        })
        .to(Sink.ignore)

    sendEmails.run()
    //#external-service-mapAsyncUnordered

    probe.receiveN(7).toSet should be(Set(
      "rolandkuhn@somewhere.com",
      "patriknw@somewhere.com",
      "bantonsson@somewhere.com",
      "drewhk@somewhere.com",
      "ktosopl@somewhere.com",
      "mmartynas@somewhere.com",
      "akkateam@somewhere.com"))
  }

  "careful managed blocking with mapAsync" in {
    val probe = TestProbe()
    val addressSystem = new AddressSystem
    val smsServer = new SmsServer(probe.ref)

    val authors = tweets.filter(_.hashtags.contains(akkaTag)).map(_.author)

    val phoneNumbers =
      authors.mapAsync(4)(author ⇒ addressSystem.lookupPhoneNumber(author.handle))
        .collect { case Some(phoneNo) ⇒ phoneNo }

    //#blocking-mapAsync
    val blockingExecutionContext = system.dispatchers.lookup("blocking-dispatcher")

    val sendTextMessages: RunnableGraph[NotUsed] =
      phoneNumbers
        .mapAsync(4)(phoneNo ⇒ {
          Future {
            smsServer.send(
              TextMessage(to = phoneNo, body = "I like your tweet"))
          }(blockingExecutionContext)
        })
        .to(Sink.ignore)

    sendTextMessages.run()
    //#blocking-mapAsync

    probe.receiveN(7).toSet should be(Set(
      "rolandkuhn".hashCode.toString,
      "patriknw".hashCode.toString,
      "bantonsson".hashCode.toString,
      "drewhk".hashCode.toString,
      "ktosopl".hashCode.toString,
      "mmartynas".hashCode.toString,
      "akkateam".hashCode.toString))
  }

  "careful managed blocking with map" in {
    val probe = TestProbe()
    val addressSystem = new AddressSystem
    val smsServer = new SmsServer(probe.ref)

    val authors = tweets.filter(_.hashtags.contains(akkaTag)).map(_.author)

    val phoneNumbers =
      authors.mapAsync(4)(author ⇒ addressSystem.lookupPhoneNumber(author.handle))
        .collect { case Some(phoneNo) ⇒ phoneNo }

    //#blocking-map
    val send = Flow[String]
      .map { phoneNo ⇒
        smsServer.send(TextMessage(to = phoneNo, body = "I like your tweet"))
      }
      .withAttributes(ActorAttributes.dispatcher("blocking-dispatcher"))
    val sendTextMessages: RunnableGraph[NotUsed] =
      phoneNumbers.via(send).to(Sink.ignore)

    sendTextMessages.run()
    //#blocking-map

    probe.expectMsg("rolandkuhn".hashCode.toString)
    probe.expectMsg("patriknw".hashCode.toString)
    probe.expectMsg("bantonsson".hashCode.toString)
    probe.expectMsg("drewhk".hashCode.toString)
    probe.expectMsg("ktosopl".hashCode.toString)
    probe.expectMsg("mmartynas".hashCode.toString)
    probe.expectMsg("akkateam".hashCode.toString)
  }

  "calling actor service with mapAsync" in {
    val probe = TestProbe()
    val database = system.actorOf(Props(classOf[DatabaseService], probe.ref), "db")

    //#save-tweets
    import akka.pattern.ask

    val akkaTweets: Source[Tweet, NotUsed] = tweets.filter(_.hashtags.contains(akkaTag))

    implicit val timeout = Timeout(3.seconds)
    val saveTweets: RunnableGraph[NotUsed] =
      akkaTweets
        .mapAsync(4)(tweet ⇒ database ? Save(tweet))
        .to(Sink.ignore)
    //#save-tweets

    saveTweets.run()

    probe.expectMsg("rolandkuhn")
    probe.expectMsg("patriknw")
    probe.expectMsg("bantonsson")
    probe.expectMsg("drewhk")
    probe.expectMsg("ktosopl")
    probe.expectMsg("mmartynas")
    probe.expectMsg("akkateam")
  }

  "illustrate ordering and parallelism of mapAsync" in {
    val probe = TestProbe()
    def println(s: String): Unit = {
      if (s.startsWith("after:"))
        probe.ref ! s
    }

    //#sometimes-slow-mapAsync
    implicit val blockingExecutionContext = system.dispatchers.lookup("blocking-dispatcher")
    val service = new SometimesSlowService

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(system).withInputBuffer(initialSize = 4, maxSize = 4))

    Source(List("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
      .map(elem ⇒ { println(s"before: $elem"); elem })
      .mapAsync(4)(service.convert)
      .runForeach(elem ⇒ println(s"after: $elem"))
    //#sometimes-slow-mapAsync

    probe.expectMsg("after: A")
    probe.expectMsg("after: B")
    probe.expectMsg("after: C")
    probe.expectMsg("after: D")
    probe.expectMsg("after: E")
    probe.expectMsg("after: F")
    probe.expectMsg("after: G")
    probe.expectMsg("after: H")
    probe.expectMsg("after: I")
    probe.expectMsg("after: J")
  }

  "illustrate ordering and parallelism of mapAsyncUnordered" in {
    val probe = TestProbe()
    def println(s: String): Unit = {
      if (s.startsWith("after:"))
        probe.ref ! s
    }

    //#sometimes-slow-mapAsyncUnordered
    implicit val blockingExecutionContext = system.dispatchers.lookup("blocking-dispatcher")
    val service = new SometimesSlowService

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(system).withInputBuffer(initialSize = 4, maxSize = 4))

    Source(List("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
      .map(elem ⇒ { println(s"before: $elem"); elem })
      .mapAsyncUnordered(4)(service.convert)
      .runForeach(elem ⇒ println(s"after: $elem"))
    //#sometimes-slow-mapAsyncUnordered

    probe.receiveN(10).toSet should be(Set(
      "after: A",
      "after: B",
      "after: C",
      "after: D",
      "after: E",
      "after: F",
      "after: G",
      "after: H",
      "after: I",
      "after: J"))
  }

  "illustrate use of source queue" in {
    //#source-queue
    val bufferSize = 5
    val elementsToProcess = 3

    val queue = Source
      .queue[Int](bufferSize, OverflowStrategy.backpressure)
      .throttle(elementsToProcess, 3.second)
      .map(x ⇒ x * x)
      .toMat(Sink.foreach(x ⇒ println(s"completed $x")))(Keep.left)
      .run()

    val source = Source(1 to 10)

    implicit val ec = system.dispatcher
    source.mapAsync(1)(x ⇒ {
      queue.offer(x).map {
        case QueueOfferResult.Enqueued    ⇒ println(s"enqueued $x")
        case QueueOfferResult.Dropped     ⇒ println(s"dropped $x")
        case QueueOfferResult.Failure(ex) ⇒ println(s"Offer failed ${ex.getMessage}")
        case QueueOfferResult.QueueClosed ⇒ println("Source Queue closed")
      }
    }).runWith(Sink.ignore)
    //#source-queue
  }

}
