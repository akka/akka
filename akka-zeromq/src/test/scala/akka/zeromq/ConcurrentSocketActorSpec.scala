/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.Actor._
import akka.actor.{Actor, ActorRef}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Duration
import akka.util.duration._
import java.util.Arrays
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import java.util.concurrent.CountDownLatch
import collection.mutable.ListBuffer

class ConcurrentSocketActorSpec extends WordSpec with MustMatchers with TestKit {
  val endpoint = "tcp://127.0.0.1:10000"
  "ConcurrentSocketActor" should {
    "support pub-sub connections" in {
      checkZeroMQInstallation
      val (publisherProbe, subscriberProbe) = (TestProbe(), TestProbe())
      var context: Option[Context] = None
      var publisher: Option[ActorRef] = None
      var subscriber: Option[ActorRef] = None
      var msgGenerator: Option[ActorRef] = None
      try {
        context = Some(ZeroMQ.newContext())
        publisher = newPublisher(context.get, publisherProbe.ref)
        subscriber = newSubscriber(context.get, subscriberProbe.ref)
        msgGenerator = newMessageGenerator(publisher)
        subscriberProbe.expectMsg(Connecting)
        val msgNumbers = subscriberProbe.receiveWhile(2 seconds) {
          case msg: ZMQMessage => msg
        }.map(_.firstFrameAsString.toInt)
        msgNumbers.length must be > 0
        msgNumbers must equal(for (i <- msgNumbers.head to msgNumbers.last) yield i)
      } finally {
        context.foreach { context =>
          msgGenerator.foreach { msgGenerator =>
            msgGenerator.stop
            within(2 seconds) {
              awaitCond(msgGenerator.isShutdown)
            }
          }
          subscriber.foreach(_.stop)
          publisher.foreach(_.stop)
          subscriberProbe.receiveWhile(1 seconds) {
            case msg => msg
          }.last must equal(Closed)
          context.term
        }
      }
    }
    "support zero-length message frames" in {
      checkZeroMQInstallation
      val publisherProbe = TestProbe()
      var publisher: Option[ActorRef] = None
      var context: Option[Context] = None
      try {
        context = Some(ZeroMQ.newContext())
        publisher = newPublisher(context.get, publisherProbe.ref)
        publisher ! ZMQMessage(Seq[Frame]())
      } finally {
        context.foreach { context =>
          publisher.foreach(_.stop)
          publisherProbe.within(5 seconds) {
            publisherProbe.expectMsg(Closed)
          }
          context.term
        }
      }
    }
    def newPublisher(context: Context, listener: ActorRef) = {
      val publisher = ZeroMQ.newSocket(SocketParameters(context, SocketType.Pub, Some(listener)))
      publisher ! Bind(endpoint)
      Some(publisher)
    }
    def newSubscriber(context: Context, listener: ActorRef) = {
      val subscriber = ZeroMQ.newSocket(SocketParameters(context, SocketType.Sub, Some(listener)))
      subscriber ! Connect(endpoint)
      subscriber ! Subscribe(Seq())
      Some(subscriber)
    }
    def newMessageGenerator(actorRef: Option[ActorRef]) = {
      Some(actorOf(new MessageGeneratorActor(actorRef)).start)
    }
    def checkZeroMQInstallation = try {
      ZeroMQ.version match {
        case ZeroMQVersion(2, 1, _) => Unit
        case version => invalidZeroMQVersion(version)
      }
    } catch {
      case e: LinkageError => zeroMQNotInstalled
    }
    def invalidZeroMQVersion(version: ZeroMQVersion) {
      info("WARNING: The tests are not run because invalid ZeroMQ version: %s. Version >= 2.1.x required.".format(version))
      pending
    }
    def zeroMQNotInstalled {
      info("WARNING: The tests are not run because ZeroMQ is not installed. Version >= 2.1.x required.")
      pending
    }
  }
  class MessageGeneratorActor(actorRef: Option[ActorRef]) extends Actor {
    var messageNumber: Int = 0
    self.receiveTimeout = Some(10)
    def receive: Receive = {
      case _ => 
        val payload = "%s".format(messageNumber)
        messageNumber = messageNumber + 1
        actorRef ! ZMQMessage(payload.getBytes)
    }
  }
}
