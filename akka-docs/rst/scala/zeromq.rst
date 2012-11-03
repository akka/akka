
.. _zeromq-scala:

################
 ZeroMQ (Scala)
################


Akka provides a ZeroMQ module which abstracts a ZeroMQ connection and therefore allows interaction between Akka actors to take place over ZeroMQ connections. The messages can be of a proprietary format or they can be defined using Protobuf. The socket actor is fault-tolerant by default and when you use the newSocket method to create new sockets it will properly reinitialize the socket.

ZeroMQ is very opinionated when it comes to multi-threading so configuration option `akka.zeromq.socket-dispatcher` always needs to be configured to a PinnedDispatcher, because the actual ZeroMQ socket can only be accessed by the thread that created it.

The ZeroMQ module for Akka is written against an API introduced in JZMQ, which uses JNI to interact with the native ZeroMQ library. Instead of using JZMQ, the module uses ZeroMQ binding for Scala that uses the native ZeroMQ library through JNA. In other words, the only native library that this module requires is the native ZeroMQ library.
The benefit of the scala library is that you don't need to compile and manage native dependencies at the cost of some runtime performance. The scala-bindings are compatible with the JNI bindings so they are a drop-in replacement, in case you really need to get that extra bit of performance out.

Connection
==========

ZeroMQ supports multiple connectivity patterns, each aimed to meet a different set of requirements. Currently, this module supports publisher-subscriber connections and connections based on dealers and routers. For connecting or accepting connections, a socket must be created.
Sockets are always created using the ``akka.zeromq.ZeroMQExtension``, for example:

.. includecode:: code/docs/zeromq/ZeromqDocSpec.scala#pub-socket


Above examples will create a ZeroMQ Publisher socket that is Bound to the port 1233 on localhost.

Similarly you can create a subscription socket, with a listener, that subscribes to all messages from the publisher using:

.. includecode:: code/docs/zeromq/ZeromqDocSpec.scala#sub-socket

The following sub-sections describe the supported connection patterns and how they can be used in an Akka environment. However, for a comprehensive discussion of connection patterns, please refer to `ZeroMQ -- The Guide <http://zguide.zeromq.org/page:all>`_.

Publisher-Subscriber Connection
-------------------------------

In a publisher-subscriber (pub-sub) connection, the publisher accepts one or more subscribers. Each subscriber shall
subscribe to one or more topics, whereas the publisher publishes messages to a set of topics. Also, a subscriber can
subscribe to all available topics. In an Akka environment, pub-sub connections shall be used when an actor sends messages
to one or more actors that do not interact with the actor that sent the message.

When you're using zeromq pub/sub you should be aware that it needs multicast - check your cloud - to work properly and that the filtering of events for topics happens client side, so all events are always broadcasted to every subscriber.

An actor is subscribed to a topic as follows:

.. includecode:: code/docs/zeromq/ZeromqDocSpec.scala#sub-topic-socket

It is a prefix match so it is subscribed to all topics starting with ``foo.bar``. Note that if the given string is empty or
``SubscribeAll`` is used, the actor is subscribed to all topics.

To unsubscribe from a topic you do the following:

.. includecode:: code/docs/zeromq/ZeromqDocSpec.scala#unsub-topic-socket

To publish messages to a topic you must use two Frames with the topic in the first frame.

.. includecode:: code/docs/zeromq/ZeromqDocSpec.scala#pub-topic

Pub-Sub in Action
^^^^^^^^^^^^^^^^^

The following example illustrates one publisher with two subscribers.

The publisher monitors current heap usage and system load and periodically publishes ``Heap`` events on the ``"health.heap"`` topic
and ``Load`` events on the ``"health.load"`` topic.

.. includecode:: code/docs/zeromq/ZeromqDocSpec.scala#health

Let's add one subscriber that logs the information. It subscribes to all topics starting with ``"health"``, i.e. both ``Heap`` and
``Load`` events.

.. includecode:: code/docs/zeromq/ZeromqDocSpec.scala#logger

Another subscriber keep track of used heap and warns if too much heap is used. It only subscribes to ``Heap`` events.

.. includecode:: code/docs/zeromq/ZeromqDocSpec.scala#alerter

Router-Dealer Connection
------------------------

While Pub/Sub is nice the real advantage of zeromq is that it is a "lego-box" for reliable messaging. And because there are so many integrations the multi-language support is fantastic.
When you're using ZeroMQ to integrate many systems you'll probably need to build your own ZeroMQ devices. This is where the router and dealer socket types come in handy.
With those socket types you can build your own reliable pub sub broker that uses TCP/IP and does publisher side filtering of events.

To create a Router socket that has a high watermark configured, you would do:

.. includecode:: code/docs/zeromq/ZeromqDocSpec.scala#high-watermark

The akka-zeromq module accepts most if not all the available configuration options for a zeromq socket.

Push-Pull Connection
--------------------

Akka ZeroMQ module supports ``Push-Pull`` connections.

You can create a ``Push`` connection through the::

    def newPushSocket(socketParameters: Array[SocketOption]): ActorRef

You can create a ``Pull`` connection through the::

    def newPullSocket(socketParameters: Array[SocketOption]): ActorRef

More documentation and examples will follow soon.

Rep-Req Connection
------------------

Akka ZeroMQ module supports ``Rep-Req`` connections.

You can create a ``Rep`` connection through the::

    def newRepSocket(socketParameters: Array[SocketOption]): ActorRef

You can create a ``Req`` connection through the::

    def newReqSocket(socketParameters: Array[SocketOption]): ActorRef

More documentation and examples will follow soon.

