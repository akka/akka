/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.scalatest.matchers.MustMatchers
import akka.testkit.{ TestProbe, DefaultTimeout, AkkaSpec }
import akka.util.duration._
import akka.actor.{ Cancellable, Actor, Props, ActorRef }

object ConcurrentSocketActorSpec {
  val config = ""
}

class ConcurrentSocketActorSpec
  extends AkkaSpec(ConcurrentSocketActorSpec.config)
  with MustMatchers
  with DefaultTimeout {

  val endpoint = "tcp://127.0.0.1:%s" format { val s = new java.net.ServerSocket(0); try s.getLocalPort finally s.close() }

  def zmq = ZeroMQExtension(system)

  "ConcurrentSocketActor" should {
    "support pub-sub connections" in {
      checkZeroMQInstallation
      val publisherProbe = TestProbe()
      val subscriberProbe = TestProbe()
      val context = Context()
      val publisher = newPublisher(context, publisherProbe.ref)
      val subscriber = newSubscriber(context, subscriberProbe.ref)
      val msgGenerator = newMessageGenerator(publisher)

      try {
        subscriberProbe.expectMsg(Connecting)
        val msgNumbers = subscriberProbe.receiveWhile(2 seconds) {
          case msg: ZMQMessage ⇒ msg
        }.map(_.firstFrameAsString.toInt)
        msgNumbers.length must be > 0
        msgNumbers must equal(for (i ← msgNumbers.head to msgNumbers.last) yield i)
      } finally {
        system stop msgGenerator
        within(2 seconds) { awaitCond(msgGenerator.isTerminated) }
        system stop publisher
        system stop subscriber
        subscriberProbe.receiveWhile(1 seconds) {
          case msg ⇒ msg
        }.last must equal(Closed)
        context.term
      }
    }
    "support zero-length message frames" in {
      checkZeroMQInstallation
      val publisherProbe = TestProbe()
      val context = Context()
      val publisher = newPublisher(context, publisherProbe.ref)

      try {
        publisher ! ZMQMessage(Seq[Frame]())
      } finally {
        system stop publisher
        publisherProbe.within(5 seconds) {
          publisherProbe.expectMsg(Closed)
        }
        context.term
      }
    }
    def newPublisher(context: Context, listener: ActorRef) = {
      zmq.newSocket(SocketType.Pub, context, Listener(listener), Bind(endpoint))
    }
    def newSubscriber(context: Context, listener: ActorRef) = {
      zmq.newSocket(SocketType.Sub, context, Listener(listener), Connect(endpoint), SubscribeAll)
    }
    def newMessageGenerator(actorRef: ActorRef) = {
      system.actorOf(Props(new MessageGeneratorActor(actorRef)))

    }
    def checkZeroMQInstallation = try {
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
  }
  class MessageGeneratorActor(actorRef: ActorRef) extends Actor {
    var messageNumber: Int = 0

    private var genMessages: Cancellable = null

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
