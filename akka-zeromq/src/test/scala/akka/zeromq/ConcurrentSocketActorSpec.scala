/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.scalatest.matchers.MustMatchers
import akka.testkit.{ TestProbe, DefaultTimeout, AkkaSpec }
import akka.util.Timeout
import akka.util.duration._
import java.net.{ SocketException, ConnectException, Socket }
import util.Random
import akka.actor.{ Cancellable, Actor, Props, ActorRef }

object ConcurrentSocketActorSpec {
  val config = """
akka {
  extensions = ["akka.zeromq.ZeroMQExtension$"]
  zeromq {
    socket-dispatcher {
      type = "PinnedDispatcher"
    }
  }
}
"""
}

class ConcurrentSocketActorSpec
  extends AkkaSpec(ConcurrentSocketActorSpec.config)
  with MustMatchers
  with DefaultTimeout {

  val endpoint = "tcp://127.0.0.1:%s" format FreePort.randomFreePort()

  def zmq = system.extension(ZeroMQExtension)

  "ConcurrentSocketActor" should {
    "support pub-sub connections" in {
      checkZeroMQInstallation
      val (publisherProbe, subscriberProbe) = (TestProbe(), TestProbe())
      val context = zmq.newContext()
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
      val context = zmq.newContext()
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
      val publisher = zmq.newSocket(SocketType.Pub, context = context, listener = Some(listener))
      publisher ! Bind(endpoint)
      publisher
    }
    def newSubscriber(context: Context, listener: ActorRef) = {
      val subscriber = zmq.newSocket(SocketType.Sub, context = context, listener = Some(listener))
      subscriber ! Connect(endpoint)
      subscriber ! Subscribe(Seq())
      subscriber
    }
    def newMessageGenerator(actorRef: ActorRef) = {
      system.actorOf(Props(new MessageGeneratorActor(actorRef)).withTimeout(Timeout(10 millis)))

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
      genMessages = system.scheduler.schedule(100 millis, 10 millis, self, 'm)
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
        messageNumber = messageNumber + 1
        actorRef ! ZMQMessage(payload.getBytes)
    }
  }

  object FreePort {

    def isPortFree(port: Int) = {
      try {
        val socket = new Socket("127.0.0.1", port)
        socket.close()
        false
      } catch {
        case e: ConnectException ⇒ true
        case e: SocketException if e.getMessage == "Connection reset by peer" ⇒ true
      }
    }

    private def newPort = Random.nextInt(55365) + 10000

    def randomFreePort(maxRetries: Int = 50) = {
      var count = 0
      var freePort = newPort
      while (!isPortFree(freePort)) {
        freePort = newPort
        count += 1
        if (count >= maxRetries) {
          throw new RuntimeException("Couldn't determine a free port")
        }
      }
      freePort
    }
  }
}
