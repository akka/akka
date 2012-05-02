
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
and send messages over a great variety of protocols and APIs. This section gives
a brief overview of the general ideas behind the akka-camel module, the
remaining sections go into the details. In addition to the native Scala and Java
actor API, actors can now exchange messages with other systems over large number
of protocols and APIs such as HTTP, SOAP, TCP, FTP, SMTP or JMS, to mention a
few. At the moment, approximately 80 protocols and APIs are supported.

Apache Camel
------------
The akka-camel module is based on `Apache Camel`_, a powerful and leight-weight
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

.. includecode:: code/akka/docs/camel/Introduction.scala#Consumer-mina

The above example exposes an actor over a tcp endpoint on port 6200 via Apache
Camel's `Mina component`_. The actor implements the endpointUri method to define
an endpoint from which it can receive messages. After starting the actor, tcp
clients can immediately send messages to and receive responses from that
actor. If the message exchange should go over HTTP (via Camel's `Jetty
component`_), only the actor's endpointUri method must be changed.

.. _Mina component: http://camel.apache.org/mina.html
.. _Jetty component: http://camel.apache.org/jetty.html

.. includecode:: code/akka/docs/camel/Introduction.scala#Consumer

Producer
--------
Actors can also trigger message exchanges with external systems i.e. produce to
Camel endpoints.

.. includecode:: code/akka/docs/camel/Introduction.scala
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
matching, transformation, serialization or storage.

__ https://svn.apache.org/repos/asf/camel/trunk/camel-core/src/main/java/org/apache/camel/Message.java


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

.. includecode:: code/akka/docs/camel/Consumers.scala#Consumer1

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

.. includecode:: code/akka/docs/camel/Consumers.scala#Consumer2

After starting the actor, clients can send messages to that actor by POSTing to
``http://localhost:8877/camel/default``. The actor sends a response by using the
self.reply method (Scala). For returning a message body and headers to the HTTP
client the response type should be `Message`_. For any other response type, a
new Message object is created by akka-camel with the actor response as message
body.

.. _Message: http://github.com/akka/akka/blob/master/akka-camel/src/main/scala/akka/camel/CamelMessage.scala
