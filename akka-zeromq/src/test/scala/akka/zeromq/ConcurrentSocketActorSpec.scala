/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import language.postfixOps

import org.scalatest.Matchers
import akka.testkit.{ TestProbe, DefaultTimeout, AkkaSpec }
import scala.concurrent.duration._
import akka.actor.{ Cancellable, Actor, Props, ActorRef }
import akka.util.{ ByteString, Timeout }

class ConcurrentSocketActorSpec extends AkkaSpec {

  implicit val timeout: Timeout = Timeout(15 seconds)

  def checkZeroMQInstallation() =
    try {
      zmq.version match {
        case ZeroMQVersion(x, y, _) if x >= 3 || (x >= 2 && y >= 1) ⇒ Unit
        case version ⇒ invalidZeroMQVersion(version)
      }
    } catch {
      case e: LinkageError ⇒ zeroMQNotInstalled()
    }

  def invalidZeroMQVersion(version: ZeroMQVersion) {
    info("WARNING: The tests are not run because invalid ZeroMQ version: %s. Version >= 2.1.x required.".format(version))
    pending
  }

  def zeroMQNotInstalled(): Unit = {
    info("WARNING: The tests are not run because ZeroMQ is not installed. Version >= 2.1.x required.")
    pending
  }

  lazy val endpoints: Vector[String] = {
    val sockets = Vector.fill(3)(new java.net.ServerSocket(0))
    val endpoints = sockets.map(s ⇒ s"tcp://127.0.0.1:${s.getLocalPort}")
    sockets.foreach(_.close())
    endpoints
  }

  // this must stay a def for checkZeroMQInstallation() to work correctly
  def zmq = ZeroMQExtension(system)

  "ConcurrentSocketActor" should {
    "support pub-sub connections" in {
      checkZeroMQInstallation()
      val subscriberProbe = TestProbe()
      val context = Context()
      val endpoint = endpoints(0)
      val publisher = zmq.newSocket(SocketType.Pub, context, Bind(endpoint))
      val subscriber = zmq.newSocket(SocketType.Sub, context, Listener(subscriberProbe.ref), Connect(endpoint), SubscribeAll)
      import system.dispatcher
      val msgGenerator = system.scheduler.schedule(100 millis, 10 millis, new Runnable {
        var number = 0
        def run() {
          publisher ! ZMQMessage(ByteString(number.toString), ByteString.empty)
          number += 1
        }
      })

      try {
        subscriberProbe.expectMsg(Connecting)
        val msgNumbers = subscriberProbe.receiveWhile(3 seconds) {
          case msg: ZMQMessage if msg.frames.size == 2 ⇒
            msg.frames(1).length should be(0)
            msg
        }.map(m ⇒ m.frames(0).utf8String.toInt)
        msgNumbers.length should be > 0
        msgNumbers should be(for (i ← msgNumbers.head to msgNumbers.last) yield i)
      } finally {
        msgGenerator.cancel()
        watch(subscriber)
        system stop subscriber
        subscriberProbe.receiveWhile(3 seconds) {
          case msg ⇒ msg
        }.last should be(Closed)
        expectTerminated(subscriber, 5.seconds)
        watch(publisher)
        system stop publisher
        expectTerminated(publisher, 5.seconds)
        context.term()
      }
    }

    "support req-rep connections" in {
      checkZeroMQInstallation()
      val requesterProbe = TestProbe()
      val replierProbe = TestProbe()
      val context = Context()
      val endpoint = endpoints(1)
      val requester = zmq.newSocket(SocketType.Req, context, Listener(requesterProbe.ref), Bind(endpoint))
      val replier = zmq.newSocket(SocketType.Rep, context, Listener(replierProbe.ref), Connect(endpoint))

      try {
        replierProbe.expectMsg(Connecting)
        val request = ZMQMessage(ByteString("Request"))
        val reply = ZMQMessage(ByteString("Reply"))

        requester ! request
        replierProbe.expectMsg(request)
        replier ! reply
        requesterProbe.expectMsg(reply)
      } finally {
        watch(replier)
        system stop replier
        replierProbe.expectMsg(Closed)
        expectTerminated(replier, 5.seconds)
        watch(requester)
        system stop requester
        expectTerminated(requester, 5.seconds)
        context.term()
      }
    }

    "should support push-pull connections" in {
      checkZeroMQInstallation()
      val pullerProbe = TestProbe()
      val context = Context()
      val endpoint = endpoints(2)
      val pusher = zmq.newSocket(SocketType.Push, context, Bind(endpoint))
      val puller = zmq.newSocket(SocketType.Pull, context, Listener(pullerProbe.ref), Connect(endpoint))

      try {
        pullerProbe.expectMsg(Connecting)
        val message = ZMQMessage(ByteString("Pushed message"))

        pusher ! message
        pullerProbe.expectMsg(message)
      } finally {

        watch(puller)
        system stop puller
        pullerProbe.expectMsg(Closed)
        expectTerminated(puller, 5.seconds)
        watch(pusher)
        system stop pusher
        expectTerminated(pusher, 5.seconds)
        context.term()
      }
    }

  }

  class MessageGeneratorActor(actorRef: ActorRef) extends Actor {
    var messageNumber: Int = 0
    var genMessages: Cancellable = null

    override def preStart() = {
      import system.dispatcher
      genMessages = system.scheduler.schedule(100 millis, 10 millis, self, "genMessage")
    }

    override def postStop() = {
      if (genMessages != null && !genMessages.isCancelled) {
        genMessages.cancel
        genMessages = null
      }
    }

    def receive = {
      case _ ⇒
        val payload = "%s".format(messageNumber)
        messageNumber += 1
        actorRef ! ZMQMessage(ByteString(payload))
    }
  }
}
