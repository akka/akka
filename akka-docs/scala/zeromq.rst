
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

ZeroMQ supports multiple connectivity patterns, each aimed to meet a different set of requirements. Currently, this module supports publisher-subscriber connections and connections based on dealers and routers. For connecting or accepting connections, a socket must be created. Sockets are always created using the methods provided by ``akka.zeromq.ZeroMQ``, for example:

.. code-block:: scala

  val socket = ZeroMQ.newSocket(SocketParameters(context, SocketType.Pub))

after which the socket is either bound to an address and port or connected, for example:

.. code-block:: scala
  
  socket ! Bind("tcp://127.0.0.1:1234")

or:

.. code-block:: scala

  socket ! Connect("tcp://localhost:1234")

Also, a socket may be created with a listener that receives received messages as well as notification:

.. code-block:: scala

  val listener = actorOf(new Actor {
    def receive: Receive = {
      case Connecting => ...
      case _ => ...
    }
  }).start
  val socket = ZeroMQ.newSocket(SocketParameters(context, SocketType.Sub, Some(listener)))
  socket ! Connect("tcp://localhost:1234")

The following sub-sections describe the supported connection patterns and how they can be used in an Akka environment. However, for a comprehensive discussion of connection patterns, please refer to `ZeroMQ -- The Guide <http://zguide.zeromq.org/page:all>`_.

Publisher-subscriber connection
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In a publisher-subscriber (pub-sub) connection, the publisher accepts one or more subscribers. Each subscriber shall subscribe to one or more topics, whereas the publisher publishes messages to a set of topics. Also, a subscriber can subscribe to all available topics. An actor is subscribed to a topic as follows:

.. code-block:: scala

  val socket = ZeroMQ.newSocket(SocketParameters(context, SocketType.Sub, Some(listener)))
  socket ! Connect("tcp://localhost:1234")
  socket ! Subscribe("SomeTopic1")

Note that if the given string is empty (see below), the actor is subscribed to all topics. Accordingly, the actor is unsubscribed as follows:

.. code-block:: scala

  socket ! Unsubscribe("SomeTopic1")

In an Akka environment, pub-sub connections shall be used when an actor sends messages to one or more actors that do not interact with the actor that sent the message. The following piece of code creates an publisher actor, binds the socket, and sends a message to be published:

.. code-block:: scala

  import akka.zeromq._
  val socket = ZeroMQ.newSocket(SocketParameters(context, SocketType.Pub))
  socket ! Bind("tcp://127.0.0.1:1234")
  socket ! ZMQMessage("hello".getBytes)

In the following code, the subscriber is configured to receive messages for all topics:

.. code-block:: scala

  import akka.zeromq._
  val listener = actorOf(new Actor {
    def receive: Receive = {
      case message: ZMQMessage => ...
      case _ => ...
    }
  }).start
  val socket = ZMQ.newSocket(SocketParameters(context, SocketType.Sub, Some(listener)))
  socket ! Connect("tcp://127.0.0.1:1234")
  socket ! Subscribe(Seq())
