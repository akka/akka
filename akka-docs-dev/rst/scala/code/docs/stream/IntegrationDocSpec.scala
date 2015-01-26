/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import scala.concurrent.duration._
import akka.stream.testkit.AkkaSpec
import akka.stream.scaladsl.Source
import java.util.Date
import akka.stream.FlowMaterializer
import scala.concurrent.Future
import akka.stream.scaladsl.RunnableFlow
import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import akka.stream.scaladsl.OperationAttributes
import scala.concurrent.ExecutionContext
import akka.stream.MaterializerSettings
import java.util.concurrent.atomic.AtomicInteger

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
      case Save(tweet: Tweet) =>
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

}

class IntegrationDocSpec extends AkkaSpec(IntegrationDocSpec.config) {
  import TwitterStreamQuickstartDocSpec._
  import IntegrationDocSpec._

  implicit val mat = FlowMaterializer()

  "calling external service with mapAsync" in {
    val probe = TestProbe()
    val addressSystem = new AddressSystem
    val emailServer = new EmailServer(probe.ref)

    //#tweet-authors
    val authors: Source[Author] =
      tweets
        .filter(_.hashtags.contains(akka))
        .map(_.author)
    //#tweet-authors

    //#email-addresses-mapAsync
    val emailAddresses: Source[String] =
      authors
        .mapAsync(author => addressSystem.lookupEmail(author.handle))
        .collect { case Some(emailAddress) => emailAddress }
    //#email-addresses-mapAsync

    //#send-emails
    val sendEmails: RunnableFlow =
      emailAddresses
        .mapAsync { address =>
          emailServer.send(
            Email(to = address, title = "Akka", body = "I like your tweet"))
        }
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

  "calling external service with mapAsyncUnordered" in {
    val probe = TestProbe()
    val addressSystem = new AddressSystem
    val emailServer = new EmailServer(probe.ref)

    //#external-service-mapAsyncUnordered
    val authors: Source[Author] =
      tweets.filter(_.hashtags.contains(akka)).map(_.author)

    val emailAddresses: Source[String] =
      authors
        .mapAsyncUnordered(author => addressSystem.lookupEmail(author.handle))
        .collect { case Some(emailAddress) => emailAddress }

    val sendEmails: RunnableFlow =
      emailAddresses
        .mapAsyncUnordered { address =>
          emailServer.send(
            Email(to = address, title = "Akka", body = "I like your tweet"))
        }
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

    val authors = tweets.filter(_.hashtags.contains(akka)).map(_.author)

    val phoneNumbers =
      authors.mapAsync(author => addressSystem.lookupPhoneNumber(author.handle))
        .collect { case Some(phoneNo) => phoneNo }

    //#blocking-mapAsync
    val blockingExecutionContext = system.dispatchers.lookup("blocking-dispatcher")

    val sendTextMessages: RunnableFlow =
      phoneNumbers
        .mapAsync { phoneNo =>
          Future {
            smsServer.send(
              TextMessage(to = phoneNo, body = "I like your tweet"))
          }(blockingExecutionContext)
        }
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

    val authors = tweets.filter(_.hashtags.contains(akka)).map(_.author)

    val phoneNumbers =
      authors.mapAsync(author => addressSystem.lookupPhoneNumber(author.handle))
        .collect { case Some(phoneNo) => phoneNo }

    //#blocking-map
    val sendTextMessages: RunnableFlow =
      phoneNumbers
        .section(OperationAttributes.dispatcher("blocking-dispatcher")) {
          _.map { phoneNo =>
            smsServer.send(
              TextMessage(to = phoneNo, body = "I like your tweet"))
          }
        }
        .to(Sink.ignore)

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
    val akkaTweets: Source[Tweet] = tweets.filter(_.hashtags.contains(akka))

    implicit val timeout = Timeout(3.seconds)
    val saveTweets: RunnableFlow =
      akkaTweets
        .mapAsync(tweet => database ? Save(tweet))
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

    implicit val mat = FlowMaterializer(
      MaterializerSettings(system).withInputBuffer(initialSize = 4, maxSize = 4))

    Source(List("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
      .map(elem => { println(s"before: $elem"); elem })
      .mapAsync(service.convert)
      .runForeach(elem => println(s"after: $elem"))
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

    implicit val mat = FlowMaterializer(
      MaterializerSettings(system).withInputBuffer(initialSize = 4, maxSize = 4))

    Source(List("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
      .map(elem => { println(s"before: $elem"); elem })
      .mapAsyncUnordered(service.convert)
      .runForeach(elem => println(s"after: $elem"))
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

}
