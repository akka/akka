/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.{Actor, ActorRef}
import org.zeromq.ZMQ._
import org.zeromq.{ZMQ => ZeroMQ}

object ZMQ {
  def createContext: ActorRef = {
    val context = Actor.actorOf(new ContextActor).start
    context ? Start
    context
  }
  def createPublisher(context: ActorRef, socketParameters: SocketParameters) = {
    val publisher = Actor.actorOf(new PublisherActor(socketParameters))
    context.startLink(publisher)
    publisher ? Start
    publisher
  }
  def createSubscriber(context: ActorRef, socketParameters: SocketParameters) = {
    val subscriber = Actor.actorOf(new SubscriberActor(socketParameters))
    context.startLink(subscriber)
    subscriber ? Start
    subscriber
  }
  def createDealer(context: ActorRef, socketParameters: SocketParameters) = {
    val dealer = Actor.actorOf(new DealerActor(socketParameters))
    context.startLink(dealer)
    dealer ? Start
    dealer
  }
  def createRouter(context: ActorRef, socketParameters: SocketParameters) = {
    val router = Actor.actorOf(new RouterActor(socketParameters))
    context.startLink(router)
    router ? Start
    router
  }
}
