
.. _zeromq-module:

ZeroMQ
======

.. sidebar:: Contents

   .. contents:: :local:

Module stability: **IN PROGRESS**

Akka provides a ZeroMQ module which abstracts a ZeroMQ connection and threfore allows interaction between Akka actors to take place over ZeroMQ connections. The messages can be of a proprietary format or they can be defined using protobuf. The connection is meant to be fault-tolerant through supervisor hierarchies and upon a connection failure, reconnection takes place automatically.

The ZeroMQ module for Akka is written against an API introduced in JZMQ, which uses JNI to interact with the native ZeroMQ library. Instead of using JZMQ, the module uses ZeroMQ binding for Scala that uses the native ZeroMQ library through JNA. In other words, the only native library that this module requires is the native ZeroMQ library. 

Connection
----------

ZeroMQ supports multiple connectivity patterns, each aimed to meet a different set of requirements. Currently, this module supports publisher-subscriber connections and connections based on dealers and routers. For connecting or accepting connections, a socket must be created. Sockets are always created using the methods provided by ``akka.zeromq.ZMQ``, for example:

.. code-block:: scala

  val socket = ZMQ.createPublisher(context, new SocketParameters("tcp://localhost:1234", Connect))

The following sub-sections describe the supported connection patterns and how they can be used in an Akka environment. However, for a comprehensive discussion of connection patterns, please refer to `ZeroMQ -- The Guide <http://zguide.zeromq.org/page:all>`_.

Publisher-subscriber connection
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In a publisher-subscriber (pub-sub) connection, the publisher accepts one or more subscribers. Each subscriber shall subscribe to one or more topics, whereas the publisher publishes messages to a set of topics. Also, a subscriber can subscribe to all available topics. An actor is subscribed to a topic as follows:

.. code-block:: scala

  val socket = ZMQ.createSubscriber(...)
  socket ? Subscribe("SomeTopic1")

Note that if the given string is empty (see below), the actor is subscribed to all available topics. Accordingly, the actor is unsubscribed as follows:

.. code-block:: scala

  socket ? Unsubscribe("SomeTopic1")

In an Akka environment, pub-sub connections shall be used when an actor sends messages to one or more actors that do not interact with the actor that sent the message. The following piece of code creates an publisher actor using ``ZMQ#createPublisher`` and sends a message to be published:

.. code-block:: scala

  import akka.zeromq._
  val publisher = ZMQ.createPublisher(context, new SocketParameters("tcp://localhost:1234", Bind))
  publisher ! ZMQMessage("hello".getBytes)

Similarly, a subscriber actor is created using ``ZMQ#createSubscriber``, while providing an ActorRef to a listener, which receives receives messages from the publisher. In the following code, the subscriber is configured to receive messages for all topics:

.. code-block:: scala

  import akka.zeromq._
  val listener = new Actor(
    def receive: Receive = {
      case message: ZMQMessage => {
        // Process message
      }
    }
  ).start
  val subscriber = ZMQ.createSubscriber(context, new SocketParameters("tcp://localhost:1234", Connect, Some(listener)))
  subscriber ! Subscribe(Array.empty)

Asynchronous client-server connection
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Given the flexibility of ZeroMQ, both the client and server may acts as ZeroMQ dealers allowing asynchronous delivery of messages. If either the client or server acts a router, then the destination address must be specified in the message frame, see ZeroMQ -- The Guide for more information.

A dealer actor is created using ZMQ#createDealer, and accordingly, a router actor is created using ZMQ#createRouter.

In the following code, the dealer acotr that binds to the socket is called the server.

.. code-block:: scala

  import akka.zeromq._
  val listener = actorOf(new Actor {
    def receive: Receive = {
      case message: ZMQMessage => {
        // Process message
      }
    }
  }).start
  val server = ZMQ.createDealer(context, new SocketParameters("tcp://localhost:1234", Bind, Some(listener)))
  server ! ZMQMessage("ping".getBytes)

And similarly, the dealer actor that connects to the endpoint is called the client.

.. code-block:: scala

  import akka.zmq._
  val listener = actorOf(new Actor {
    def receive: Receive = {
      case message: ZMQMessage => {
        // Process message
      }
    }
  }).start
  val server = ZMQ.createDealer(context, new SocketParameters("tcp://localhost:1234", Connect, Some(listener)))
  server ! ZMQMessage("pong".getBytes

As both of the endpoint act as dealers, they can be send and receive messages asynchronously over a ZeroMQ connection.
