package akka.amqp.rpc

import akka.amqp.AMQP._
import com.google.protobuf.Message
import akka.actor.{Actor, ActorRef}
import Actor._
import akka.amqp._
import reflect.Manifest
import akka.japi

object RPC {

  // Needed for Java API usage
  def newRpcClient[O, I](connection: ActorRef,
                         exchangeName: String,
                         routingKey: String,
                         serializer: RpcClientSerializer[O, I]): RpcClient[O,I] = {
    newRpcClient(connection, exchangeName, serializer, Some(routingKey), None)
  }

  // Needed for Java API usage
  def newRpcClient[O, I](connection: ActorRef,
                         exchangeName: String,
                         routingKey: String,
                         serializer: RpcClientSerializer[O, I],
                         channelParameters: ChannelParameters): RpcClient[O,I] = {
    newRpcClient(connection, exchangeName, serializer, Some(routingKey), Some(channelParameters))
  }

  def newRpcClient[O, I](connection: ActorRef,
                         exchangeName: String,
                         serializer: RpcClientSerializer[O, I],
                         routingKey: Option[String] = None,
                         channelParameters: Option[ChannelParameters] = None): RpcClient[O,I] = {

    val rKey = routingKey.getOrElse("%s.request".format(exchangeName))

    val rpcActor: ActorRef = actorOf(new RpcClientActor[O, I](
      ExchangeParameters(exchangeName, exchangeDeclaration = PassiveDeclaration), rKey, serializer, channelParameters))
    connection.startLink(rpcActor)
    rpcActor ! Start
    rpcActor
    new RpcClient(rpcActor)
  }

  // Needed for Java API usage
  def newRpcServer[I, O](connection: ActorRef,
                         exchangeName: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: japi.Function[I,O],
                         routingKey: String): RpcServerHandle = {
    newRpcServer(connection, exchangeName, serializer, requestHandler.apply _, Some(routingKey))
  }

  // Needed for Java API usage
  def newRpcServer[I, O](connection: ActorRef,
                         exchangeName: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: Function[I,O],
                         routingKey: String,
                         queueName: String): RpcServerHandle = {
    newRpcServer(connection, exchangeName, serializer, requestHandler.apply _, Some(routingKey), Some(queueName))
  }

  // Needed for Java API usage
  def newRpcServer[I, O](connection: ActorRef,
                         exchangeName: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: japi.Function[I,O],
                         routingKey: String,
                         channelParameters: ChannelParameters): RpcServerHandle = {
    newRpcServer(connection, exchangeName, serializer, requestHandler.apply _, Some(routingKey), None, Some(channelParameters))
  }

  // Needed for Java API usage
  def newRpcServer[I, O](connection: ActorRef,
                         exchangeName: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: japi.Function[I,O],
                         routingKey: String,
                         queueName: String,
                         channelParameters: ChannelParameters): RpcServerHandle = {
    newRpcServer(connection, exchangeName, serializer, requestHandler.apply _, Some(routingKey), Some(queueName), Some(channelParameters))
  }

  def newRpcServer[I, O](connection: ActorRef,
                         exchangeName: String,
                         serializer: RpcServerSerializer[I, O],
                         requestHandler: I => O,
                         routingKey: Option[String] = None,
                         queueName: Option[String] = None,
                         channelParameters: Option[ChannelParameters] = None,
                         poolSize: Int = 1): RpcServerHandle = {

    val rKey = routingKey.getOrElse("%s.request".format(exchangeName))
    val qName = queueName.getOrElse("%s.in".format(rKey))

    val producer = newProducer(connection, ProducerParameters(channelParameters = channelParameters))

    val consumers = (1 to poolSize).map {
      num =>
        val rpcServer = actorOf(new RpcServerActor[I, O](producer, serializer, requestHandler))
        newConsumer(connection, ConsumerParameters(rKey, rpcServer,
          exchangeParameters = Some(ExchangeParameters(exchangeName)), channelParameters = channelParameters,
          selfAcknowledging = false, queueName = Some(qName)))
    }
    RpcServerHandle(producer, consumers)
  }

  case class RpcServerHandle(producer: ActorRef, consumers: Seq[ActorRef]) {
    def stop = {
      consumers.foreach(_.stop)
      producer.stop
    }
  }

  case class RpcClientSerializer[O, I](toBinary: ToBinary[O], fromBinary: FromBinary[I])

  case class RpcServerSerializer[I, O](fromBinary: FromBinary[I], toBinary: ToBinary[O])


  /**
   * RPC convenience
   */
  class RpcClient[O, I](client: ActorRef){

    // Needed for Java API usage
    def call(request: O): Option[I] = {
      call(request, 5000)
    }

    def call(request: O, timeout: Long = 5000): Option[I] = {
      (client.!!(request, timeout)).as[I]
    }

    // Needed for Java API usage
    def callAsync(request: O, responseHandler: japi.Procedure[I]): Unit = {
      callAsync(request, 5000, responseHandler)
    }

    // Needed for Java API usage
    def callAsync(request: O, timeout: Long, responseHandler: japi.Procedure[I]): Unit = {
      callAsync(request, timeout){
        case Some(response) => responseHandler.apply(response)
      }
    }

    def callAsync(request: O, timeout: Long = 5000)(responseHandler: PartialFunction[Option[I],Unit]) = {
      spawn {
        val result = call(request, timeout)
        responseHandler.apply(result)
      }
    }
    def stop = client.stop
  }


  // Needed for Java API usage
  def newProtobufRpcServer[I <: Message, O <: Message](
          connection: ActorRef,
          exchangeName: String,
          requestHandler: japi.Function[I,O],
          resultClazz: Class[I]): RpcServerHandle = {

    implicit val manifest = Manifest.classType[I](resultClazz)
    newProtobufRpcServer(connection, exchangeName, requestHandler.apply _)
  }

  // Needed for Java API usage
  def newProtobufRpcServer[I <: Message, O <: Message](
          connection: ActorRef,
          exchangeName: String,
          requestHandler: japi.Function[I,O],
          routingKey: String,
          resultClazz: Class[I]): RpcServerHandle = {

    implicit val manifest = Manifest.classType[I](resultClazz)
    newProtobufRpcServer(connection, exchangeName, requestHandler.apply _, Some(routingKey))
  }

  // Needed for Java API usage
  def newProtobufRpcServer[I <: Message, O <: Message](
          connection: ActorRef,
          exchangeName: String,
          requestHandler: japi.Function[I,O],
          routingKey: String,
          queueName: String,
          resultClazz: Class[I]): RpcServerHandle = {

    implicit val manifest = Manifest.classType[I](resultClazz)
    newProtobufRpcServer(connection, exchangeName, requestHandler.apply _, Some(routingKey), Some(queueName))
  }

  def newProtobufRpcServer[I <: Message, O <: Message](
          connection: ActorRef,
          exchangeName: String,
          requestHandler: I => O,
          routingKey: Option[String] = None,
          queueName: Option[String] = None)(implicit manifest: Manifest[I]): RpcServerHandle = {

    val serializer = new RpcServerSerializer[I, O](
      new FromBinary[I] {
        def fromBinary(bytes: Array[Byte]): I = {
          createProtobufFromBytes[I](bytes)
        }
      }, new ToBinary[O] {
        def toBinary(t: O) = t.toByteArray
      })

    newRpcServer(connection, exchangeName, serializer, requestHandler, routingKey, queueName)
  }

  // Needed for Java API usage
  def newProtobufRpcClient[O <: Message, I <: Message](
          connection: ActorRef,
          exchangeName: String,
          resultClazz: Class[I]): RpcClient[O, I] = {

    implicit val manifest = Manifest.classType[I](resultClazz)
    newProtobufRpcClient(connection, exchangeName, None)
  }

  // Needed for Java API usage
  def newProtobufRpcClient[O <: Message, I <: Message](
          connection: ActorRef,
          exchangeName: String,
          routingKey: String,
          resultClazz: Class[I]): RpcClient[O, I] = {

    implicit val manifest = Manifest.classType[I](resultClazz)
    newProtobufRpcClient(connection, exchangeName, Some(routingKey))
  }

  def newProtobufRpcClient[O <: Message, I <: Message](
          connection: ActorRef,
          exchangeName: String,
          routingKey: Option[String] = None)(implicit manifest: Manifest[I]): RpcClient[O, I] = {


    val serializer = new RpcClientSerializer[O, I](
      new ToBinary[O] {
        def toBinary(t: O) = t.toByteArray
      }, new FromBinary[I] {
        def fromBinary(bytes: Array[Byte]): I = {
          createProtobufFromBytes[I](bytes)
        }
      })

    newRpcClient(connection, exchangeName, serializer, routingKey)
  }

  // Needed for Java API usage
  def newStringRpcServer(connection: ActorRef,
                        exchangeName: String,
                        requestHandler: japi.Function[String,String]): RpcServerHandle = {
    newStringRpcServer(connection, exchangeName, requestHandler.apply _)
  }

  // Needed for Java API usage
  def newStringRpcServer(connection: ActorRef,
                        exchangeName: String,
                        requestHandler: japi.Function[String,String],
                        routingKey: String): RpcServerHandle = {
    newStringRpcServer(connection, exchangeName, requestHandler.apply _, Some(routingKey))
  }

  // Needed for Java API usage
  def newStringRpcServer(connection: ActorRef,
                        exchangeName: String,
                        requestHandler: japi.Function[String,String],
                        routingKey: String,
                        queueName: String): RpcServerHandle = {
    newStringRpcServer(connection, exchangeName, requestHandler.apply _, Some(routingKey), Some(queueName))
  }

  def newStringRpcServer(connection: ActorRef,
                        exchangeName: String,
                        requestHandler: String => String,
                        routingKey: Option[String] = None,
                        queueName: Option[String] = None): RpcServerHandle = {

    val serializer = new RpcServerSerializer[String, String](
      new FromBinary[String] {
        def fromBinary(bytes: Array[Byte]): String = {
          new String(bytes)
        }
      }, new ToBinary[String] {
        def toBinary(t: String) = t.getBytes
      })

    newRpcServer(connection, exchangeName, serializer, requestHandler, routingKey, queueName)
  }

  // Needed for Java API usage
  def newStringRpcClient(connection: ActorRef,
                        exchange: String): RpcClient[String, String] = {
    newStringRpcClient(connection, exchange, None)
  }

  // Needed for Java API usage
  def newStringRpcClient(connection: ActorRef,
                        exchange: String,
                        routingKey: String): RpcClient[String, String] = {
    newStringRpcClient(connection, exchange, Some(routingKey))
  }

  def newStringRpcClient(connection: ActorRef,
                        exchange: String,
                        routingKey: Option[String] = None): RpcClient[String, String] = {


    val serializer = new RpcClientSerializer[String, String](
      new ToBinary[String] {
        def toBinary(t: String) = t.getBytes
      }, new FromBinary[String] {
        def fromBinary(bytes: Array[Byte]): String = {
          new String(bytes)
        }
      })

    newRpcClient(connection, exchange, serializer, routingKey)
  }
}

