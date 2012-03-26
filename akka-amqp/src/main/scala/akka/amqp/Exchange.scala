package akka.amqp

import com.rabbitmq.client.Channel

trait Exchange

trait NamedExchange extends Exchange {
  def name: String

  def delete(channel: Channel, ifUnused: Boolean) {
    channel.exchangeDelete(name, ifUnused)
  }
}

case object DefaultExchange extends Exchange

case class UnmanagedExchange(name: String) extends NamedExchange

trait ManagedExchange extends NamedExchange {
  def declare(channel: Channel)
}

case class PassiveExchange(name: String) extends ManagedExchange {

  def declare(channel: Channel) {
    //    if (isDebugEnabled) debug("Passivly delaring exchange '%s' on channel %s".format(name, channel))
    channel.exchangeDeclarePassive(name)
  }
}

case class ActiveExchange(name: String,
                          exchangeType: String,
                          durable: Boolean = false,
                          autoDelete: Boolean = false,
                          internal: Boolean = false,
                          arguments: Option[Map[String, AnyRef]] = None) extends ManagedExchange {
  def declare(channel: Channel) {
    import scala.collection.JavaConverters._
    //    if (isDebugEnabled) debug("Activly delaring exchange '%s' (type: %s, durable: %s, autoDelete: %s, internal: %s, arguments: %s) on channel %s"
    //      .format(name, exchangeType, durable, autoDelete, internal, arguments, channel))
    channel.exchangeDeclare(name, exchangeType, durable, autoDelete, internal, arguments.map(_.asJava).getOrElse(null))
  }
}

case class ExchangeToExchangeBinding(destination: NamedExchange,
                                     source: NamedExchange,
                                     routingKey: String,
                                     arguments: Option[Map[String, AnyRef]] = None) {

  def bind(channel: Channel) {
    import scala.collection.JavaConverters._
    channel.exchangeBind(destination.name, source.name, routingKey, arguments.map(_.asJava).getOrElse(null))
  }

  def unbind(channel: Channel) {
    import scala.collection.JavaConverters._
    channel.exchangeUnbind(destination.name, source.name, routingKey, arguments.map(_.asJava).getOrElse(null))
  }
}
