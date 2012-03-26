package akka.amqp

import com.rabbitmq.client.Channel
import com.rabbitmq.client.AMQP.Queue.DeclareOk

sealed trait Queue

trait NamedQueue {
  def name: String
}

case class UnmanagedQueue(name: String) extends NamedQueue

case class DeclaredQueue(name: String, messageCount: Int, consumerCount: Int) {
  def purge(channel: Channel) {
    channel.queuePurge(name)
  }

  def delete(channel: Channel, ifUnused: Boolean, ifEmpty: Boolean) {
    channel.queueDelete(name, ifUnused, ifEmpty)
  }
}

trait ManagedQueue extends Queue {
  def declare(channel: Channel): DeclaredQueue

  implicit def declareOkToDeclaredQueue(declareOk: DeclareOk): DeclaredQueue = {
    DeclaredQueue(declareOk.getQueue, declareOk.getMessageCount, declareOk.getConsumerCount)
  }
}

case object GeneratedQueue extends ManagedQueue {
  def declare(channel: Channel) = {
    channel.queueDeclare()
  }
}

case class PassiveQueue(name: String) extends ManagedQueue {

  def declare(channel: Channel) = {
    channel.queueDeclarePassive(name)
  }
}

case class ActiveQueue(name: String,
                       durable: Boolean = false,
                       exclusive: Boolean = false,
                       autoDelete: Boolean = true,
                       arguments: Option[Map[String, AnyRef]] = None) extends ManagedQueue {
  def declare(channel: Channel) = {
    import scala.collection.JavaConverters._
    channel.queueDeclare(name, durable, exclusive, autoDelete, arguments.map(_.asJava).getOrElse(null))
  }
}

case class QueueBinding(exchange: NamedExchange,
                        routingKey: String,
                        arguments: Option[Map[String, AnyRef]] = None) {

  def bind(channel: Channel, queueName: String) {
    import scala.collection.JavaConverters._
    channel.queueBind(queueName, exchange.name, routingKey, arguments.map(_.asJava).getOrElse(null))
  }

  def unbind(channel: Channel, queueName: String) {
    import scala.collection.JavaConverters._
    channel.queueUnbind(queueName, exchange.name, routingKey, arguments.map(_.asJava).getOrElse(null))
  }
}