
.. _zeromq-module:

ZeroMQ
======

.. sidebar:: Contents

   .. contents:: :local:

Module stability: **IN PROGRESS**

Akka provides a ZeroMQ module which abstracts a ZeroMQ connection and therefore allows interaction between Akka actors to take place over ZeroMQ connections. The messages can be of a proprietary format or they can be defined using Protobuf. The socket actor is fault-tolerant by default and when you use the newSocket method to create new sockets it will properly reinitialize the socket.

ZeroMQ is very opinionated when it comes to multi-threading so configuration option `akka.zeromq.socket-dispatcher` always needs to be configured to a PinnedDispatcher, because the actual ZeroMQ socket can only be accessed by the thread that created it.

The ZeroMQ module for Akka is written against an API introduced in JZMQ, which uses JNI to interact with the native ZeroMQ library. Instead of using JZMQ, the module uses ZeroMQ binding for Scala that uses the native ZeroMQ library through JNA. In other words, the only native library that this module requires is the native ZeroMQ library.  
The benefit of the scala library is that you don't need to compile and manage native dependencies at the cost of some runtime performance. The scala-bindings are compatible with the JNI bindings so they are a drop-in replacement, in case you really need to get that extra bit of performance out.

Connection
----------

ZeroMQ supports multiple connectivity patterns, each aimed to meet a different set of requirements. Currently, this module supports publisher-subscriber connections and connections based on dealers and routers. For connecting or accepting connections, a socket must be created. Sockets are always created using ``akka.zeromq.ZeroMQ.newSocket``, for example:

.. code-block:: scala

  import akka.zeromq._
  val socket = system.zeromq.newSocket(SocketType.Pub, Bind("tcp://127.0.0.1:1234"))

will create a ZeroMQ Publisher socket that is Bound to the port 1234 on localhost.
Importing the akka.zeromq._ package ensures that the implicit zeromq method is available.
Similarly you can create a subscribtion socket, that subscribes to all messages from the publisher using:

.. code-block:: scala

  val socket = system.zeromq.newSocket(SocketType.Sub, Connect("tcp://127.0.0.1:1234"), SubscribeAll)

Also, a socket may be created with a listener that handles received messages as well as notifications:

.. code-block:: scala

  val listener = system.actorOf(Props(new Actor {
    def receive: Receive = {
      case Connecting => ...
      case _ => ...
    }
  }))
  val socket = system.zeromq.newSocket(SocketType.Router, Listener(listener), Connect("tcp://localhost:1234"))

The following sub-sections describe the supported connection patterns and how they can be used in an Akka environment. However, for a comprehensive discussion of connection patterns, please refer to `ZeroMQ -- The Guide <http://zguide.zeromq.org/page:all>`_.

Publisher-subscriber connection
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In a publisher-subscriber (pub-sub) connection, the publisher accepts one or more subscribers. Each subscriber shall subscribe to one or more topics, whereas the publisher publishes messages to a set of topics. Also, a subscriber can subscribe to all available topics. 

When you're using zeromq pub/sub you should be aware that it needs multicast - check your cloud - to work properly and that the filtering of events for topics happens client side, so all events are always broadcasted to every subscriber.

An actor is subscribed to a topic as follows:

.. code-block:: scala

  val socket = system.zeromq.newSocket(SocketType.Sub, Listener(listener), Connect("tcp://localhost:1234"), Subscribe("the-topic"))

Note that if the given string is empty (see below), the actor is subscribed to all topics. To unsubscribe from a topic you do the following:

.. code-block:: scala

  socket ! Unsubscribe("SomeTopic1")

In an Akka environment, pub-sub connections shall be used when an actor sends messages to one or more actors that do not interact with the actor that sent the message. The following piece of code creates a publisher actor, binds the socket, and sends a message to be published:

.. code-block:: scala

  import akka.zeromq._
  val socket = system.zeromq.newSocket(SocketType.Pub, Bind("tcp://127.0.0.1:1234"))
  socket ! Send("hello".getBytes)

In the following code, the subscriber is configured to receive messages for all topics:

.. code-block:: scala

  import akka.zeromq._
  val listener = system.actorOf(Props(new Actor {
    def receive: Receive = {
      case Connecting => ...
      case _ => ...
    }
  }))
  val socket = system.zeromq.newSocket(SocketType.Sub, Listener(listener), Connect("tcp://127.0.0.1:1234"), SubscribeAll)

Router-Dealer connection
^^^^^^^^^^^^^^^^^^^^^^^^

While Pub/Sub is nice the real advantage of zeromq is that it is a "lego-box" for reliable messaging. And because there are so many integrations the multi-language support is fantastic.
When you're using ZeroMQ to integrate many systems you'll probably need to build your own ZeroMQ devices. This is where the router and dealer socket types come in handy.
With those socket types you can build your own reliable pub sub broker that uses TCP/IP and does publisher side filtering of events.

To create a Router socket that has a high watermark configured, you would do:

.. code-block:: scala
  
  import akka.zeromq._
  val listener = system.actorOf(Props(new Actor {
    def receive: Receive = {
      case Connecting => ...
      case _ => ...
    }
  }))
  val socket = system.zeromq.newSocket(
                                  SocketType.Router, 
                                  Listener(listener), 
                                  Bind("tcp://127.0.0.1:1234"), 
                                  HWM(50000))

The akka-zeromq module accepts most if not all the available configuration options for a zeromq socket.