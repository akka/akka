/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.amqp

import com.rabbitmq.client.AMQP.BasicProperties
import java.lang.{ String, IllegalArgumentException }
import reflect.Manifest
import akka.japi.Procedure
import com.rabbitmq.client._
import ConnectionFactory._
import akka.actor.{ Address ⇒ _, _ }
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout
import akka.dispatch.Await
import java.util.UUID

/**
 * AMQP Actor API. Implements Connection, Producer and Consumer materialized as Actors.
 *
 * @see akka.amqp.ExampleSession
 *
 * @author Irmo Manie
 * @author John Stanford (migration to Akka 2.0)
 */

object AMQP {

  implicit val timeout = Timeout(5 seconds)

  private lazy val system = ActorSystem("AMQPSystem")

  /**
   * Parameters used to make the connection to the amqp broker. Uses the rabbitmq defaults.
   */
  case class ConnectionParameters(
    addresses: Array[Address] = Array(new Address(DEFAULT_HOST, DEFAULT_AMQP_PORT)),
    username: String = DEFAULT_USER,
    password: String = DEFAULT_PASS,
    virtualHost: String = DEFAULT_VHOST,
    initReconnectDelay: Long = 5000,
    connectionCallback: Option[ActorRef] = None) {

    // Needed for Java API usage
    def this() = this(Array(new Address(DEFAULT_HOST, DEFAULT_AMQP_PORT)), DEFAULT_USER, DEFAULT_PASS, DEFAULT_VHOST, 5000, None)

    // Needed for Java API usage
    def this(addresses: Array[Address], username: String, password: String, virtualHost: String) =
      this(addresses, username, password, virtualHost, 5000, None)

    // Needed for Java API usage
    def this(addresses: Array[Address], username: String, password: String, virtualHost: String, initReconnectDelay: Long, connectionCallback: ActorRef) =
      this(addresses, username, password, virtualHost, initReconnectDelay, Some(connectionCallback))

    // Needed for Java API usage
    def this(connectionCallback: ActorRef) =
      this(Array(new Address(DEFAULT_HOST, DEFAULT_AMQP_PORT)), DEFAULT_USER, DEFAULT_PASS, DEFAULT_VHOST, 5000, Some(connectionCallback))

  }

  /**
   * Additional parameters for the channel
   */
  case class ChannelParameters(
    shutdownListener: Option[ShutdownListener] = None,
    channelCallback: Option[ActorRef] = None,
    prefetchSize: Int = 0) {

    // Needed for Java API usage
    def this() = this(None, None)

    // Needed for Java API usage
    def this(channelCallback: ActorRef) = this(None, Some(channelCallback))

    // Needed for Java API usage
    def this(shutdownListener: ShutdownListener, channelCallback: ActorRef) =
      this(Some(shutdownListener), Some(channelCallback))
  }

  /**
   * Declaration type used for either exchange or queue declaration
   */
  sealed trait Declaration
  case object NoActionDeclaration extends Declaration {
    def getInstance() = this // Needed for Java API usage
  }
  case object PassiveDeclaration extends Declaration {
    def getInstance() = this // Needed for Java API usage
  }
  case class ActiveDeclaration(durable: Boolean = false, autoDelete: Boolean = true, exclusive: Boolean = false) extends Declaration {

    // Needed for Java API usage
    def this() = this(false, true, false)

    // Needed for Java API usage
    def this(durable: Boolean, autoDelete: Boolean) = this(durable, autoDelete, false)
  }

  /**
   * Exchange specific parameters
   */
  case class ExchangeParameters(
    exchangeName: String,
    exchangeType: ExchangeType = Topic,
    exchangeDeclaration: Declaration = ActiveDeclaration(),
    configurationArguments: Map[String, AnyRef] = Map.empty) {

    // Needed for Java API usage
    def this(exchangeName: String) =
      this(exchangeName, Topic, ActiveDeclaration(), Map.empty)

    // Needed for Java API usage
    def this(exchangeName: String, exchangeType: ExchangeType) =
      this(exchangeName, exchangeType, ActiveDeclaration(), Map.empty)

    // Needed for Java API usage
    def this(exchangeName: String, exchangeType: ExchangeType, exchangeDeclaration: Declaration) =
      this(exchangeName, exchangeType, exchangeDeclaration, Map.empty)
  }

  /**
   * Producer specific parameters
   */
  case class ProducerParameters(
    exchangeParameters: Option[ExchangeParameters] = None,
    returnListener: Option[ReturnListener] = None,
    channelParameters: Option[ChannelParameters] = None,
    errorCallbackActor: Option[ActorRef] = None) {
    def this() = this(None, None, None, None)

    // Needed for Java API usage
    def this(exchangeParameters: ExchangeParameters) =
      this(Some(exchangeParameters), None, None, None)

    // Needed for Java API usage
    def this(exchangeParameters: ExchangeParameters, returnListener: ReturnListener) =
      this(Some(exchangeParameters), Some(returnListener), None, None)

    // Needed for Java API usage
    def this(exchangeParameters: ExchangeParameters, channelParameters: ChannelParameters) =
      this(Some(exchangeParameters), None, Some(channelParameters), None)

    // Needed for Java API usage
    def this(exchangeParameters: ExchangeParameters, returnListener: ReturnListener, channelParameters: ChannelParameters) =
      this(Some(exchangeParameters), Some(returnListener), Some(channelParameters), None)

    // Needed for Java API usage
    def this(exchangeParameters: ExchangeParameters, returnListener: ReturnListener, channelParameters: ChannelParameters, errorCallbackActor: ActorRef) =
      this(Some(exchangeParameters), Some(returnListener), Some(channelParameters), Some(errorCallbackActor))

    // Needed for Java API usage
    def this(exchangeParameters: ExchangeParameters, channelParameters: ChannelParameters, errorCallbackActor: ActorRef) =
      this(Some(exchangeParameters), None, Some(channelParameters), Some(errorCallbackActor))
  }

  /**
   * Consumer specific parameters
   */
  case class ConsumerParameters(
    routingKey: String,
    deliveryHandler: ActorRef,
    queueName: Option[String] = None,
    exchangeParameters: Option[ExchangeParameters] = None,
    queueDeclaration: Declaration = ActiveDeclaration(),
    selfAcknowledging: Boolean = true,
    channelParameters: Option[ChannelParameters] = None) {
    if (queueName.isEmpty) {
      queueDeclaration match {
        case ActiveDeclaration(true, _, _) ⇒
          throw new IllegalArgumentException("A queue name is required when requesting a durable queue.")
        case PassiveDeclaration ⇒
          throw new IllegalArgumentException("A queue name is required when requesting passive declaration.")
        case _ ⇒ () // ignore
      }
    }

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef) =
      this(routingKey, deliveryHandler, None, None, ActiveDeclaration(), true, None)

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, channelParameters: ChannelParameters) =
      this(routingKey, deliveryHandler, None, None, ActiveDeclaration(), true, Some(channelParameters))

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, selfAcknowledging: Boolean) =
      this(routingKey, deliveryHandler, None, None, ActiveDeclaration(), selfAcknowledging, None)

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, selfAcknowledging: Boolean, channelParameters: ChannelParameters) =
      this(routingKey, deliveryHandler, None, None, ActiveDeclaration(), selfAcknowledging, Some(channelParameters))

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, queueName: String) =
      this(routingKey, deliveryHandler, Some(queueName), None, ActiveDeclaration(), true, None)

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, queueDeclaration: Declaration, selfAcknowledging: Boolean, channelParameters: ChannelParameters) =
      this(routingKey, deliveryHandler, Some(queueName), None, queueDeclaration, selfAcknowledging, Some(channelParameters))

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, exchangeParameters: ExchangeParameters) =
      this(routingKey, deliveryHandler, None, Some(exchangeParameters), ActiveDeclaration(), true, None)

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, exchangeParameters: ExchangeParameters, channelParameters: ChannelParameters) =
      this(routingKey, deliveryHandler, None, Some(exchangeParameters), ActiveDeclaration(), true, Some(channelParameters))

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, exchangeParameters: ExchangeParameters, selfAcknowledging: Boolean) =
      this(routingKey, deliveryHandler, None, Some(exchangeParameters), ActiveDeclaration(), selfAcknowledging, None)

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters) =
      this(routingKey, deliveryHandler, Some(queueName), Some(exchangeParameters), ActiveDeclaration(), true, None)

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters, queueDeclaration: Declaration) =
      this(routingKey, deliveryHandler, Some(queueName), Some(exchangeParameters), queueDeclaration, true, None)

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters, queueDeclaration: Declaration, selfAcknowledging: Boolean) =
      this(routingKey, deliveryHandler, Some(queueName), Some(exchangeParameters), queueDeclaration, selfAcknowledging, None)

    // Needed for Java API usage
    def this(routingKey: String, deliveryHandler: ActorRef, queueName: String, exchangeParameters: ExchangeParameters, queueDeclaration: Declaration, selfAcknowledging: Boolean, channelParameters: ChannelParameters) =
      this(routingKey, deliveryHandler, Some(queueName), Some(exchangeParameters), queueDeclaration, selfAcknowledging, Some(channelParameters))

    // How about that for some overloading... huh? :P (yes, I know, there are still possibilities left...sue me!)
    // Who said java is easy :(
  }

  def newConnection(connectionParameters: ConnectionParameters = new ConnectionParameters()): ActorRef = {

    val connection = system.actorOf(Props(new FaultTolerantConnectionActor(connectionParameters)), "amqp-connection-" + UUID.randomUUID.toString)
    connection ! Connect
    connection
  }

  def shutdownConnection(connection: ActorRef) = {
    system.stop(connection)
  }

  // Needed for Java API usage
  def newConnection(): ActorRef = {
    newConnection(new ConnectionParameters())
  }

  def newProducer(connection: ActorRef, producerParameters: ProducerParameters) = {
    val p = connection ? ProducerRequest(producerParameters)
    Await.result(p, timeout.duration).asInstanceOf[Option[ActorRef]]
  }

  def newConsumer(connection: ActorRef, consumerParameters: ConsumerParameters) = {
    val c = connection ? ConsumerRequest(consumerParameters) mapTo manifest[Option[ActorRef]]
    Await.result(c, timeout.duration).asInstanceOf[Option[ActorRef]]
  }

  /**
   *  Convenience
   */
  class ProducerClient[O](client: ActorRef, routingKey: String, toBinary: ToBinary[O]) {
    // Needed for Java API usage
    def send(request: O): Unit = {
      send(request, None)
    }
    // Needed for Java API usage
    def send(request: O, replyTo: String): Unit = {
      send(request, Some(replyTo))
    }

    def send(request: O, replyTo: Option[String] = None) = {

      val basicProperties = new BasicProperties.Builder().replyTo(replyTo.getOrElse(null)).build()
      client ! Message(toBinary.toBinary(request), routingKey, false, false, Some(basicProperties))
    }

    def stop() = client ! PoisonPill
  }

  // Needed for Java API usage
  def newStringProducer(connection: ActorRef,
                        exchangeName: String): ProducerClient[String] = {
    newStringProducer(connection, Some(exchangeName))
  }

  // Needed for Java API usage
  def newStringProducer(connection: ActorRef,
                        exchangeName: String,
                        routingKey: String): ProducerClient[String] = {
    newStringProducer(connection, Some(exchangeName), Some(routingKey))
  }

  def newStringProducer(connection: ActorRef,
                        exchangeName: Option[String],
                        routingKey: Option[String] = None): ProducerClient[String] = {

    if (exchangeName.isEmpty && routingKey.isEmpty) {
      throw new IllegalArgumentException("Either exchange name or routing key is mandatory")
    }
    val exchangeParameters = exchangeName.flatMap(name ⇒ Some(ExchangeParameters(name)))
    val rKey = routingKey.getOrElse("%s.request".format(exchangeName.get))

    val producerRef = newProducer(connection, ProducerParameters(exchangeParameters))
    val toBinary = new ToBinary[String] {
      def toBinary(t: String) = t.getBytes
    }
    new ProducerClient(producerRef.getOrElse(throw new NoSuchElementException("Could not create producer")), rKey, toBinary)
  }

  // Needed for Java API usage
  def newStringConsumer(connection: ActorRef,
                        handler: Procedure[String],
                        exchangeName: String): ActorRef = {
    newStringConsumer(connection, handler.apply _, Some(exchangeName))
  }

  // Needed for Java API usage
  def newStringConsumer(connection: ActorRef,
                        handler: Procedure[String],
                        exchangeName: String,
                        routingKey: String): ActorRef = {
    newStringConsumer(connection, handler.apply _, Some(exchangeName), Some(routingKey))
  }

  // Needed for Java API usage
  def newStringConsumer(connection: ActorRef,
                        handler: Procedure[String],
                        exchangeName: String,
                        routingKey: String,
                        queueName: String): ActorRef = {
    newStringConsumer(connection, handler.apply _, Some(exchangeName), Some(routingKey), Some(queueName))
  }

  def newStringConsumer(connection: ActorRef,
                        handler: String ⇒ Unit,
                        exchangeName: Option[String],
                        routingKey: Option[String] = None,
                        queueName: Option[String] = None): ActorRef = {

    if (exchangeName.isEmpty && routingKey.isEmpty) {
      throw new IllegalArgumentException("Either exchange name or routing key is mandatory")
    }

    val deliveryHandler = system.actorOf(Props(new Actor {
      def receive = { case Delivery(payload, _, _, _, _, _) ⇒ handler.apply(new String(payload)) }
    }))

    val exchangeParameters = exchangeName.flatMap(name ⇒ Some(ExchangeParameters(name)))
    val rKey = routingKey.getOrElse("%s.request".format(exchangeName.get))
    val qName = queueName.getOrElse("%s.in".format(rKey))

    newConsumer(connection, ConsumerParameters(rKey, deliveryHandler, Some(qName), exchangeParameters)).
      getOrElse(throw new NoSuchElementException("Could not create consumer"))
  }

  // Needed for Java API usage
  def newProtobufProducer[O <: com.google.protobuf.Message](connection: ActorRef,
                                                            exchangeName: String): ProducerClient[O] = {
    newProtobufProducer(connection, Some(exchangeName))
  }

  // Needed for Java API usage
  def newProtobufProducer[O <: com.google.protobuf.Message](connection: ActorRef,
                                                            exchangeName: String,
                                                            routingKey: String): ProducerClient[O] = {
    newProtobufProducer(connection, Some(exchangeName), Some(routingKey))
  }

  def newProtobufProducer[O <: com.google.protobuf.Message](connection: ActorRef,
                                                            exchangeName: Option[String],
                                                            routingKey: Option[String] = None): ProducerClient[O] = {

    if (exchangeName.isEmpty && routingKey.isEmpty) {
      throw new IllegalArgumentException("Either exchange name or routing key is mandatory")
    }
    val exchangeParameters = exchangeName.flatMap(name ⇒ Some(ExchangeParameters(name)))
    val rKey = routingKey.getOrElse("%s.request".format(exchangeName.get))

    val producerRef = newProducer(connection, ProducerParameters(exchangeParameters))
    new ProducerClient(producerRef.getOrElse(throw new NoSuchElementException("Could not create producer")), rKey, new ToBinary[O] {
      def toBinary(t: O) = t.toByteArray
    })
  }

  // Needed for Java API usage
  def newProtobufConsumer[I <: com.google.protobuf.Message](connection: ActorRef,
                                                            handler: Procedure[I],
                                                            exchangeName: String,
                                                            clazz: Class[I]): ActorRef = {
    implicit val manifest = Manifest.classType[I](clazz)
    newProtobufConsumer[I](connection, handler.apply _, Some(exchangeName))
  }

  // Needed for Java API usage
  def newProtobufConsumer[I <: com.google.protobuf.Message](connection: ActorRef,
                                                            handler: Procedure[I],
                                                            exchangeName: String,
                                                            routingKey: String,
                                                            clazz: Class[I]): ActorRef = {
    implicit val manifest = Manifest.classType[I](clazz)
    newProtobufConsumer[I](connection, handler.apply _, Some(exchangeName), Some(routingKey))
  }

  // Needed for Java API usage
  def newProtobufConsumer[I <: com.google.protobuf.Message](connection: ActorRef,
                                                            handler: Procedure[I],
                                                            exchangeName: String,
                                                            routingKey: String,
                                                            queueName: String,
                                                            clazz: Class[I]): ActorRef = {
    implicit val manifest = Manifest.classType[I](clazz)
    newProtobufConsumer[I](connection, handler.apply _, Some(exchangeName), Some(routingKey), Some(queueName))
  }

  def newProtobufConsumer[I <: com.google.protobuf.Message](connection: ActorRef,
                                                            handler: I ⇒ Unit,
                                                            exchangeName: Option[String],
                                                            routingKey: Option[String] = None,
                                                            queueName: Option[String] = None)(implicit manifest: Manifest[I]): ActorRef = {

    if (exchangeName.isEmpty && routingKey.isEmpty) {
      throw new IllegalArgumentException("Either exchange name or routing key is mandatory")
    }

    val deliveryHandler = system.actorOf(Props(new Actor {
      def receive = { case Delivery(payload, _, _, _, _, _) ⇒ handler.apply(createProtobufFromBytes[I](payload)) }
    }))

    val exchangeParameters = exchangeName.flatMap(name ⇒ Some(ExchangeParameters(name)))
    val rKey = routingKey.getOrElse("%s.request".format(exchangeName.get))
    val qName = queueName.getOrElse("%s.in".format(rKey))

    newConsumer(connection, ConsumerParameters(rKey, deliveryHandler, Some(qName), exchangeParameters)).
      getOrElse(throw new NoSuchElementException("Could not create consumer"))
  }

  def shutdownAll() = {
    system.actorSelection("/user/*") ! PoisonPill
  }

  /**
   * Serialization stuff
   */

  trait FromBinary[T] {
    def fromBinary(bytes: Array[Byte]): T
  }

  trait ToBinary[T] {
    def toBinary(t: T): Array[Byte]
  }

  private val ARRAY_OF_BYTE_ARRAY = Array[Class[_]](classOf[Array[Byte]])

  private[amqp] def createProtobufFromBytes[I <: com.google.protobuf.Message](bytes: Array[Byte])(implicit manifest: Manifest[I]): I = {
    manifest.erasure.getDeclaredMethod("parseFrom", ARRAY_OF_BYTE_ARRAY: _*).invoke(null, bytes).asInstanceOf[I]
  }
}
