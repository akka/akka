/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.{Actor, ActorRef}
import akka.testkit.TestKit
import akka.util.Duration
import akka.util.duration._
import java.util.Arrays
import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec

class ConcurrentSocketActorSpec extends WordSpec with MustMatchers with TestKit {
  val endpoint = "inproc://PubSubConnectionSpec"
  "ConcurrentSocketActor" should {
    "support pub-sub connections" in {
      val message = ZMQMessage("hello".getBytes)
      var context: Option[Context] = None
      var publisher: Option[ActorRef] = None
      var subscriber: Option[ActorRef] = None
      try {
        context = Some(ZeroMQ.newContext)
        publisher = newPublisher(context.get)
        subscriber = newSubscriber(context.get)
        within (5000 millis) {
          expectMsg(Connected)
          publisher ! message
          expectMsg(message)
        }
      } finally {
        subscriber ! Close
        publisher ! Close
        within (5000 millis) {
          expectMsg(Closed)
        }
      }
    }
    def newPublisher(context: Context) = {
      val publisher = ZeroMQ.newSocket(context, SocketType.Pub)
      publisher ! Bind(endpoint)
      Some(publisher)
    }
    def newSubscriber(context: Context) = {
      val subscriber = ZeroMQ.newSocket(context, SocketType.Sub, Some(testActor))
      subscriber ! Connect(endpoint)
      subscriber ! Subscribe(Seq())
      Some(subscriber)
    }
  }
}
