/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.actor.Actor._

import com.rabbitmq.client.ConnectionParameters

object ExampleSession {
  import AMQP._
  val CONFIG = new ConnectionParameters
  val HOSTNAME = "localhost"
  val PORT = 5672

  val IM = "im.whitehouse.gov"
  val CHAT = "chat.whitehouse.gov"

  def main(args: Array[String]) = {
    println("==== DIRECT ===")
    direct

    Thread.sleep(1000)
    
    println("==== FANOUT ===")
    fanout
  }

  def direct = {
    val consumer = AMQP.newConsumer(CONFIG, HOSTNAME, PORT, IM, ExchangeType.Direct, None, 100, false, false, false, Map[String, AnyRef]())
    consumer ! MessageConsumerListener("@george_bush", "direct", actor {
      case Message(payload, _, _, _, _) => log.info("@george_bush received message from: %s", new String(payload.asInstanceOf[Array[Byte]]))
    })
    val producer = AMQP.newProducer(CONFIG, HOSTNAME, PORT, IM, None, None, 100)
    producer ! Message("@jonas_boner: You sucked!!".getBytes, "direct")
  }

  def fanout = {
    val consumer = AMQP.newConsumer(CONFIG, HOSTNAME, PORT, CHAT, ExchangeType.Fanout, None, 100, false, false, false, Map[String, AnyRef]())
    consumer ! MessageConsumerListener("@george_bush", "", actor {
      case Message(payload, _, _, _, _) => log.info("@george_bush received message from: %s", new String(payload.asInstanceOf[Array[Byte]]))
    })
    consumer ! MessageConsumerListener("@barack_obama", "", actor {
      case Message(payload, _, _, _, _) => log.info("@barack_obama received message from: %s", new String(payload.asInstanceOf[Array[Byte]]))
    })
    val producer = AMQP.newProducer(CONFIG, HOSTNAME, PORT, CHAT, None, None, 100)
    producer ! Message("@jonas_boner: I'm going surfing".getBytes, "")
  }
}