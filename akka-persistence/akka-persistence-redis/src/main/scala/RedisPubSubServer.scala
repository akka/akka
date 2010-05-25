package se.scalablesolutions.akka.persistence.redis

import se.scalablesolutions.akka.actor.Actor
import com.redis._

sealed trait Msg
case class Subscribe(channels: Array[String]) extends Msg
case class Register(callback: PubSubMessage => Any) extends Msg
case class Unsubscribe(channels: Array[String]) extends Msg
case object UnsubscribeAll extends Msg
case class Publish(channel: String, msg: String) extends Msg

class Subscriber(client: RedisClient) extends Actor {
  var callback: PubSubMessage => Any = { m => }

  def receive = {
    case Subscribe(channels) =>
      client.subscribe(channels.head, channels.tail: _*)(callback)
      self.reply(true)

    case Register(cb) =>
      callback = cb
      self.reply(true)

    case Unsubscribe(channels) =>
      client.unsubscribe(channels.head, channels.tail: _*)
      self.reply(true)

    case UnsubscribeAll =>
      client.unsubscribe
      self.reply(true)
  }
}

class Publisher(client: RedisClient) extends Actor {
  def receive = {
    case Publish(channel, message) =>
      client.publish(channel, message)
      self.reply(true)
  }
}

