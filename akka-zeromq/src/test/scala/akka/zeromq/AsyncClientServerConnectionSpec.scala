/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.{ Actor, ActorRef }
import org.scalatest.{ BeforeAndAfter }
import akka.zeromq.test.Specification

class AsyncClientServerConnectionSpec extends Specification with BeforeAndAfter {
  "Asynchronous client-server connection" should {
    var endpoint: String = ""
    val clientListener = Actor.actorOf(new ListenerActor).start
    val serverListener = Actor.actorOf(new ListenerActor).start
    before {
      endpoint = "tcp://127.0.0.1:" + randomPort
      serverListener ? ClearMessages
      clientListener ? ClearMessages
    }
    "send N requests, receive 0 responses" in {
      val (server, client) = (createServer, createClient)
      waitUntil {
        client ? ZMQMessage("request".getBytes)
        serverMessages.length >= 1
      }
      serverMessages.length must be >=(1)
      client.stop
      server.stop
    }
    "send N requests, receive M responses" in {
      val (client, server) = (createClient, createServer)
      waitUntil {
        client ? ZMQMessage("request".getBytes)
        server ? ZMQMessage("response".getBytes)
        clientMessages.length >= 1 && serverMessages.length >= 1 
      }
      serverMessages.length must be >=(1)
      clientMessages.length must be >=(1)
      client.stop
      server.stop
    }
    "send 0 requests, receive N responses" in {
      val (client, server) = (createClient, createServer)
      waitUntil {
        server ? ZMQMessage("response".getBytes)
        clientMessages.length >= 1
      }
      clientMessages.length must be >=(1)
      client.stop
      server.stop
    }
    def createClient = {
      ZMQ.createDealer(context, new SocketParameters(endpoint, Connect, Some(clientListener), true))
    }
    def createServer = {
      ZMQ.createDealer(context, new SocketParameters(endpoint, Bind, Some(serverListener), true))
    }
    def serverMessages = {
      messages(serverListener)
    }
    def clientMessages = {
      messages(clientListener)
    }
    def messages(listener: ActorRef) = {
      (listener ? QueryMessages).mapTo[List[ZMQMessage]].get
    }
    lazy val context = ZMQ.createContext
  }
  class ListenerActor extends Actor {
    private var messages = List[ZMQMessage]()
    def receive: Receive = {
      case message: ZMQMessage ⇒ {
        messages = message :: messages
      }
      case QueryMessages ⇒ {
        self.reply(messages)
      }
      case ClearMessages ⇒ {
        messages = List[ZMQMessage]()
        self.reply(messages)
      }
    }
  }
  case object QueryMessages
  case object ClearMessages
}
