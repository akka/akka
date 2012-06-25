
.. _camel-scala:

#######
 Camel
#######

Additional Resources
====================
For an introduction to akka-camel 2, see also the Peter Gabryanczyk's talk `Migrating akka-camel module to Akka 2.x`_.

For an introduction to akka-camel 1, see also the `Appendix E - Akka and Camel`_
(pdf) of the book `Camel in Action`_.

.. _Appendix E - Akka and Camel: http://www.manning.com/ibsen/appEsample.pdf
.. _Camel in Action: http://www.manning.com/ibsen/
.. _Migrating akka-camel module to Akka 2.x: http://skillsmatter.com/podcast/scala/akka-2-x

Other, more advanced external articles (for version 1) are:

* `Akka Consumer Actors: New Features and Best Practices <http://krasserm.blogspot.com/2011/02/akka-consumer-actors-new-features-and.html>`_
* `Akka Producer Actors: New Features and Best Practices <http://krasserm.blogspot.com/2011/02/akka-producer-actor-new-features-and.html>`_

Introduction
============

The akka-camel module allows actors to receive
and send messages over a great variety of protocols and APIs (akka-camel version 1.x also provided
this functionality to Typed Actors. In akka-camel 2, support for Typed Actors has been removed.)
This section gives a brief overview of the general ideas behind the akka-camel module, the
remaining sections go into the details. In addition to the native Scala and Java
actor API, actors can now exchange messages with other systems over large number
of protocols and APIs such as HTTP, SOAP, TCP, FTP, SMTP or JMS, to mention a
few. At the moment, approximately 80 protocols and APIs are supported.

Apache Camel
------------
The akka-camel module is based on `Apache Camel`_, a powerful and light-weight
integration framework for the JVM. For an introduction to Apache Camel you may
want to read this `Apache Camel article`_. Camel comes with a
large number of `components`_ that provide bindings to different protocols and
APIs. The `camel-extra`_ project provides further components.

.. _Apache Camel: http://camel.apache.org/
.. _Apache Camel article: http://architects.dzone.com/articles/apache-camel-integration
.. _components: http://camel.apache.org/components.html
.. _camel-extra: http://code.google.com/p/camel-extra/

Consumer
--------
Usage of Camel's integration components in Akka is essentially a
one-liner. Here's an example.

.. includecode:: code/docs/camel/Introduction.scala#Consumer-mina

The above example exposes an actor over a tcp endpoint on port 6200 via Apache
Camel's `Mina component`_. The actor implements the endpointUri method to define
an endpoint from which it can receive messages. After starting the actor, tcp
clients can immediately send messages to and receive responses from that
actor. If the message exchange should go over HTTP (via Camel's `Jetty
component`_), only the actor's endpointUri method must be changed.

.. _Mina component: http://camel.apache.org/mina.html
.. _Jetty component: http://camel.apache.org/jetty.html

.. includecode:: code/docs/camel/Introduction.scala#Consumer

Producer
--------
Actors can also trigger message exchanges with external systems i.e. produce to
Camel endpoints.

.. includecode:: code/docs/camel/Introduction.scala
                :include: imports,Producer

In the above example, any message sent to this actor will be sent to
the JMS queue ``orders``. Producer actors may choose from the same set of Camel
components as Consumer actors do.

CamelMessage
------------
The number of Camel components is constantly increasing. The akka-camel module
can support these in a plug-and-play manner. Just add them to your application's
classpath, define a component-specific endpoint URI and use it to exchange
messages over the component-specific protocols or APIs. This is possible because
Camel components bind protocol-specific message formats to a Camel-specific
`normalized message format`__. The normalized message format hides
protocol-specific details from Akka and makes it therefore very easy to support
a large number of protocols through a uniform Camel component interface. The
akka-camel module further converts mutable Camel messages into immutable
representations which are used by Consumer and Producer actors for pattern
matching, transformation, serialization or storage. In the above example of the Orders Producer,
the XML message is put in the body of a newly created Camel Message with an empty set of headers.
You can also create a CamelMessage yourself with the appropriate body and headers as you see fit.

__ https://svn.apache.org/repos/asf/camel/trunk/camel-core/src/main/java/org/apache/camel/Message.java

CamelExtension
--------------
The akka-camel module is implemented as an Akka Extension, the ``CamelExtension`` object.
Extensions will only be loaded once per ``ActorSystem``, which will be managed by Akka. There is a one to one relationship between the ``CamelExtension`` and
the ``ActorSystem``, there can be only one ``CamelExtension`` for one ``ActorSystem``.
The ``CamelExtension`` object provides access to the `Camel`_ trait.
The `Camel`_ trait in turn provides access to two important Apache Camel objects, the `CamelContext`_ and the `ProducerTemplate`_.
Below you can see how you can get access to these Apache Camel objects.

.. includecode:: code/docs/camel/Introduction.scala#CamelExtension

One ``CamelExtension`` is only loaded once for every one ``ActorSystem``, which makes it safe to call the ``CamelExtension`` at any point in your code to get to the
Apache Camel objects associated with it. There is one `CamelContext`_ and one `ProducerTemplate`_ for every one ``ActorSystem`` that uses a ``CamelExtension``.
Below an example on how to add the ActiveMQ component to the `CamelContext`_, which is required when you would like to use the ActiveMQ component.

.. includecode:: code/docs/camel/Introduction.scala#CamelExtensionAddComponent

The `CamelContext`_ joins the lifecycle of the ``ActorSystem`` and ``CamelExtension`` it is associated with; the `CamelContext`_ is started when
the ``CamelExtension`` is created, and it is shutdown when the associated ``ActorSystem`` is shut down. The same is true for the `ProducerTemplate`_.

The ``CamelExtension`` is used by both `Producer` and `Consumer` actors to interact with Apache Camel internally.
When Akka creates a `Consumer` actor, the `Consumer` is published at its
Camel endpoint (more precisely, the route is added to the `CamelContext`_ from the `Endpoint`_ to the actor).
When Akka creates a `Producer` actor, a `SendProcessor`_ and `Endpoint`_ are created so that the Producer can send messages to it.
Publication is done asynchronously; setting up an endpoint may still be in progress after you have
requested the actor to be created. Some Camel components can take a while to startup, and in some cases you might want to know when the endpoints are activated and ready to be used.
The `Camel`_ trait allows you to find out when the endpoint is activated or deactivated.

.. includecode:: code/docs/camel/Introduction.scala#CamelActivation

The above code shows that you can get a ``Future`` to the activation of the route from the endpoint to the actor, or you can wait in a blocking fashion on the activation of the route.
An ``ActivationTimeoutException`` is thrown if the endpoint could not be activated within the specified timeout. Deactivation works in a similar fashion:

.. includecode:: code/docs/camel/Introduction.scala#CamelDeactivation

Deactivation of a Consumer or a Producer actor happens when the actor is terminated. For a Consumer, the route to the actor is stopped. For a Producer, the `SendProcessor`_ is stopped.
A ``DeActivationTimeoutException`` is thrown if the associated camel objects could not be deactivated within the specified timeout.

.. _Camel: http://github.com/akka/akka/blob/master/akka-camel/src/main/scala/akka/camel/Camel.scala
.. _CamelContext: https://svn.apache.org/repos/asf/camel/trunk/camel-core/src/main/java/org/apache/camel/CamelContext.java
.. _ProducerTemplate: https://svn.apache.org/repos/asf/camel/trunk/camel-core/src/main/java/org/apache/camel/ProducerTemplate.java
.. _SendProcessor: https://svn.apache.org/repos/asf/camel/trunk/camel-core/src/main/java/org/apache/camel/processor/SendProcessor.java
.. _Endpoint: https://svn.apache.org/repos/asf/camel/trunk/camel-core/src/main/java/org/apache/camel/Endpoint.java

Dependencies
============

SBT
---
.. code-block:: scala

    "com.typesafe.akka" % "akka-camel" % "2.1-SNAPSHOT"

Maven
-----
.. code-block:: xml

    <dependency>
        <groupId>com.typesafe.akka</groupId>
        <artifactId>akka-camel</artifactId>
        <version>2.1-SNAPSHOT</version>
    </dependency>

.. _camel-consumer-actors:


Consumer Actors
================

For objects to receive messages, they must mixin the `Consumer`_
trait. For example, the following actor class (Consumer1) implements the
endpointUri method, which is declared in the Consumer trait, in order to receive
messages from the ``file:data/input/actor`` Camel endpoint.

.. _Consumer: http://github.com/akka/akka/blob/master/akka-camel/src/main/scala/akka/camel/Consumer.scala

.. includecode:: code/docs/camel/Consumers.scala#Consumer1

Whenever a file is put into the data/input/actor directory, its content is
picked up by the Camel `file component`_ and sent as message to the
actor. Messages consumed by actors from Camel endpoints are of type
`CamelMessage`_. These are immutable representations of Camel messages.

.. _file component: http://camel.apache.org/file2.html
.. _Message: http://github.com/akka/akka/blob/master/akka-camel/src/main/scala/akka/camel/CamelMessage.scala


Here's another example that sets the endpointUri to
``jetty:http://localhost:8877/camel/default``. It causes Camel's `Jetty
component`_ to start an embedded `Jetty`_ server, accepting HTTP connections
from localhost on port 8877.

.. _Jetty component: http://camel.apache.org/jetty.html
.. _Jetty: http://www.eclipse.org/jetty/

.. includecode:: code/docs/camel/Consumers.scala#Consumer2

After starting the actor, clients can send messages to that actor by POSTing to
``http://localhost:8877/camel/default``. The actor sends a response by using the
self.reply method (Scala). For returning a message body and headers to the HTTP
client the response type should be `Message`_. For any other response type, a
new Message object is created by akka-camel with the actor response as message
body.

.. _Message: http://github.com/akka/akka/blob/master/akka-camel/src/main/scala/akka/camel/CamelMessage.scala

Acknowledgements
----------------

With in-out message exchanges, clients usually know that a message exchange is
done when they receive a reply from a consumer actor. The reply message can be a
CamelMessage (or any object which is then internally converted to a CamelMessage) on
success, and a Failure message on failure.

With in-only message exchanges, by default, an exchange is done when a message
is added to the consumer actor's mailbox. Any failure or exception that occurs
during processing of that message by the consumer actor cannot be reported back
to the endpoint in this case. To allow consumer actors to positively or
negatively acknowledge the receipt of a message from an in-only message
exchange, they need to override the ``autoack`` method to return false.
In this case, consumer actors must reply either with a
special Ack message (positive acknowledgement) or a Failure (negative
acknowledgement).

.. includecode:: code/docs/camel/Consumers.scala#Consumer3

Consumer timeout
----------------

Camel Exchanges (and their corresponding endpoints) that support two-way communications need to wait for a response from
an actor before returning it to the initiating client.
For some endpoint types, timeout values can be defined in an endpoint-specific
way which is described in the documentation of the individual `Camel
components`_. Another option is to configure timeouts on the level of consumer
actors.

.. _Camel components: http://camel.apache.org/components.html

Two-way communications between a Camel endpoint and an actor are
initiated by sending the request message to the actor with the ask pattern
and the actor replies to the endpoint when the response is ready. The ask request to the actor can timeout, which will
result in the `Exchange`_ failing with a TimeoutException set on the failure of the `Exchange`_.
The timeout on the consumer actor can be overridden with the ``replyTimeout``, as shown below.

.. includecode:: code/docs/camel/Consumers.scala#Consumer4
.. _Exchange: https://svn.apache.org/repos/asf/camel/trunk/camel-core/src/main/java/org/apache/camel/Exchange.java

Producer Actors
===============

For sending messages to Camel endpoints, actors need to mixin the `Producer`_ trait and implement the endpointUri method.

.. includecode:: code/docs/camel/Producers.scala#Producer1
.. _Producer: http://github.com/akka/akka/blob/master/akka-camel/src/main/scala/akka/camel/Producer.scala




    Actors (untyped)
    Consumer publishing
    Actors (untyped)
    Typed actors
    Consumers and the CamelService
    Consumer un-publishing
    Actors (untyped)
    Typed actors
    Acknowledgements
    Actors (untyped)
    Blocking exchanges
    Consumer timeout
    Typed actors
    Actors (untyped)
    Remote consumers
    Actors (untyped)
    Typed actors
Produce messages
    Producer trait
    Actors (untyped)
    Custom Processing
    Producer configuration options
    Message correlation
    Matching responses
    ProducerTemplate
    Actors (untyped)
    Typed actors
    Asynchronous routing
Fault tolerance
CamelService configuration
Standalone applications
Standalone Spring applications
Kernel mode
Custom Camel routes
Akka Camel components
Access to actors
URI options
Message headers
Access to typed actors
Using Spring
Without Spring
Intercepting route construction
Actors (untyped)
Typed actors
Examples
Asynchronous routing and transformation example
Custom Camel route example
Publish-subcribe example
JMS
Cometd
Quartz Scheduler Example

