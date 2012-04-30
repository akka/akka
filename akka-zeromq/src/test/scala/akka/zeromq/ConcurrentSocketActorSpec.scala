/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.scalatest.matchers.MustMatchers
import akka.testkit.{ TestProbe, DefaultTimeout, AkkaSpec }
import akka.util.duration._
import akka.actor.{ Cancellable, Actor, Props, ActorRef }
import akka.util.Timeout

class ConcurrentSocketActorSpec extends AkkaSpec {

  implicit val timeout: Timeout = Timeout(15 seconds)

  def checkZeroMQInstallation =
    try {
      zmq.version match {
        case ZeroMQVersion(2, 1, _) ⇒ Unit
        case version                ⇒ invalidZeroMQVersion(version)
      }
    } catch {
      case e: LinkageError ⇒ zeroMQNotInstalled
    }

  def invalidZeroMQVersion(version: ZeroMQVersion) {
    info("WARNING: The tests are not run because invalid ZeroMQ version: %s. Version >= 2.1.x required.".format(version))
    pending
  }

  def zeroMQNotInstalled {
    info("WARNING: The tests are not run because ZeroMQ is not installed. Version >= 2.1.x required.")
    pending
  }

  val endpoint = "tcp://127.0.0.1:%s" format { val s = new java.net.ServerSocket(0); try s.getLocalPort finally s.close() }

  // this must stay a def for checkZeroMQInstallation to work correctly
  def zmq = ZeroMQExtension(system)

  "ConcurrentSocketActor" should {
    "support pub-sub connections" in {
      checkZeroMQInstallation
      val subscriberProbe = TestProbe()
      val context = Context()
      val publisher = zmq.newSocket(SocketType.Pub, context, Bind(endpoint))
      val subscriber = zmq.newSocket(SocketType.Sub, context, Listener(subscriberProbe.ref), Connect(endpoint), SubscribeAll)
      val msgGenerator = system.scheduler.schedule(100 millis, 10 millis, new Runnable {
        var number = 0
        def run() {
          publisher ! ZMQMessage(Seq(Frame(number.toString.getBytes), Frame(Seq())))
          number += 1
        }
      })

      try {
        subscriberProbe.expectMsg(Connecting)
        val msgNumbers = subscriberProbe.receiveWhile(2 seconds) {
          case msg: ZMQMessage if msg.frames.size == 2 ⇒
            msg.payload(1).length must be(0)
            msg
        }.map(_.firstFrameAsString.toInt)
        msgNumbers.length must be > 0
        msgNumbers must equal(for (i ← msgNumbers.head to msgNumbers.last) yield i)
      } finally {
        msgGenerator.cancel()
        system stop publisher
        system stop subscriber
        subscriberProbe.receiveWhile(1 seconds) {
          case msg ⇒ msg
        }.last must equal(Closed)
        awaitCond(publisher.isTerminated())
        awaitCond(subscriber.isTerminated())
        context.term
      }
    }

    "support req-rep connections" in {
      checkZeroMQInstallation
      val requesterProbe = TestProbe()
      val replierProbe = TestProbe()
      val context = Context()
      val requester = zmq.newSocket(SocketType.Req, context, Listener(requesterProbe.ref), Bind(endpoint))
      val replier = zmq.newSocket(SocketType.Rep, context, Listener(replierProbe.ref), Connect(endpoint))

      try {
        replierProbe.expectMsg(Connecting)
        val request = ZMQMessage(Seq(Frame("Request")))
        val reply = ZMQMessage(Seq(Frame("Reply")))

        requester ! request
        replierProbe.expectMsg(request)
        replier ! reply
        requesterProbe.expectMsg(reply)
      } finally {
        system stop requester
        system stop replier
        replierProbe.expectMsg(Closed)
        context.term
      }
    }

    "should support push-pull connections" in {
      checkZeroMQInstallation
      val pullerProbe = TestProbe()
      val context = Context()
      val pusher = zmq.newSocket(SocketType.Push, context, Bind(endpoint))
      val puller = zmq.newSocket(SocketType.Pull, context, Listener(pullerProbe.ref), Connect(endpoint))

      try {
        pullerProbe.expectMsg(Connecting)
        val message = ZMQMessage(Seq(Frame("Pushed message")))

        pusher ! message
        pullerProbe.expectMsg(message)
      } finally {
        system stop pusher
        system stop puller
        pullerProbe.expectMsg(Closed)
        context.term
      }
    }

  }

  class MessageGeneratorActor(actorRef: ActorRef) extends Actor {
    var messageNumber: Int = 0
    var genMessages: Cancellable = null

    override def preStart() = {
      genMessages = system.scheduler.schedule(100 millis, 10 millis, self, "genMessage")
    }

    override def postStop() = {
      if (genMessages != null && !genMessages.isCancelled) {
        genMessages.cancel
        genMessages = null
      }
    }

    protected def receive = {
      case _ ⇒
        val payload = "%s".format(messageNumber)
        messageNumber += 1
        actorRef ! ZMQMessage(payload.getBytes)
    }
  }
}
