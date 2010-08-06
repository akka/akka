package se.scalablesolutions.akka.amqp.rpc

import se.scalablesolutions.akka.amqp.AMQP._
import com.google.protobuf.Message
import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import Actor._
import se.scalablesolutions.akka.amqp._

object RPC {

  def newRpcClient[O, I](connection: ActorRef,
                         exchangeParameters: ExchangeParameters,
                         routingKey: String,
                         serializer: RpcClientSerializer[O, I],
                         channelParameters: Option[ChannelParameters] = None): ActorRef = {
    val rpcActor: ActorRef = actorOf(new RpcClientActor[O, I](exchangeParameters, routingKey, serializer, channelParameters))
    connection.startLink(rpcActor)
    rpcActor ! Start
    rpcActor
  }

  def newRpcServer[I, O](connection: ActorRef,
                         exchangeParameters: ExchangeParameters,
                         routingKey: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: I => O,
                         queueName: Option[String] = None,
                         channelParameters: Option[ChannelParameters] = None) = {
    val producer = newProducer(connection, new ProducerParameters(new ExchangeParameters("", ExchangeType.Direct), channelParameters = channelParameters))
    val rpcServer = actorOf(new RpcServerActor[I, O](producer, serializer, requestHandler))
    val consumer = newConsumer(connection, new ConsumerParameters(exchangeParameters, routingKey, rpcServer
      , channelParameters = channelParameters
      , selfAcknowledging = false
      , queueName = queueName))

  }

  trait FromBinary[T] {
    def fromBinary(bytes: Array[Byte]): T
  }

  trait ToBinary[T] {
    def toBinary(t: T): Array[Byte]
  }


  case class RpcClientSerializer[O, I](toBinary: ToBinary[O], fromBinary: FromBinary[I])

  case class RpcServerSerializer[I, O](fromBinary: FromBinary[I], toBinary: ToBinary[O])


  /**
   * RPC convenience
   */
  class RpcClient[O, I](client: ActorRef){
    def callService(request: O, timeout: Long = 5000): Option[I] = {
      (client.!!(request, timeout)).as[I]
    }
  }

  private val ARRAY_OF_BYTE_ARRAY = Array[Class[_]](classOf[Array[Byte]])

  def startProtobufServer[I <: Message, O <: Message](
          connection: ActorRef, serviceName: String, requestHandler: I => O)(implicit manifest: Manifest[I]) = {

    val serializer = new RpcServerSerializer[I, O](
      new FromBinary[I] {
        def fromBinary(bytes: Array[Byte]): I = {
          createProtobufFromBytes[I](bytes)
        }
      }, new ToBinary[O] {
        def toBinary(t: O) = t.toByteArray
      })

    val exchangeParameters = new ExchangeParameters(serviceName, ExchangeType.Topic)
    val routingKey = "%s.request".format(serviceName)
    val queueName = "%s.in".format(routingKey)

    newRpcServer[I, O](connection, exchangeParameters, routingKey, serializer, requestHandler,
      queueName = Some(queueName))
  }

  def startProtobufClient[O <: Message, I <: Message](
          connection: ActorRef, serviceName: String)(implicit manifest: Manifest[I]): RpcClient[O, I] = {

    val serializer = new RpcClientSerializer[O, I](
      new ToBinary[O] {
        def toBinary(t: O) = t.toByteArray
      }, new FromBinary[I] {
        def fromBinary(bytes: Array[Byte]): I = {
          createProtobufFromBytes[I](bytes)
        }
      })

    val exchangeParameters = new ExchangeParameters(serviceName, ExchangeType.Topic)
    val routingKey = "%s.request".format(serviceName)
    val queueName = "%s.in".format(routingKey)

    val client = newRpcClient[O, I](connection, exchangeParameters, routingKey, serializer)
    new RpcClient[O, I](client)
  }

  private def createProtobufFromBytes[I](bytes: Array[Byte])(implicit manifest: Manifest[I]): I = {
    manifest.erasure.getDeclaredMethod("parseFrom", ARRAY_OF_BYTE_ARRAY: _*).invoke(null, bytes).asInstanceOf[I]
  }
}