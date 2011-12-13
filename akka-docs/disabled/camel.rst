
.. _camel-module:

#######
 Camel
#######

=======
.. _Appendix E - Akka and Camel: http://www.manning.com/ibsen/appEsample.pdf
.. _Camel in Action: http://www.manning.com/ibsen/

Contents:

.. contents:: :local:

Other, more advanced external articles are:

* `Akka Consumer Actors: New Features and Best Practices <http://krasserm.blogspot.com/2011/02/akka-consumer-actors-new-features-and.html>`_
* `Akka Producer Actors: New Features and Best Practices <http://krasserm.blogspot.com/2011/02/akka-producer-actor-new-features-and.html>`_


Introduction
============

The akka-camel module allows actors, untyped actors, and typed actors to receive
and send messages over a great variety of protocols and APIs. This section gives
a brief overview of the general ideas behind the akka-camel module, the
remaining sections go into the details. In addition to the native Scala and Java
actor API, actors can now exchange messages with other systems over large number
of protocols and APIs such as HTTP, SOAP, TCP, FTP, SMTP or JMS, to mention a
few. At the moment, approximately 80 protocols and APIs are supported.

The akka-camel module is based on `Apache Camel`_, a powerful and leight-weight
integration framework for the JVM. For an introduction to Apache Camel you may
want to read this `Apache Camel article`_. Camel comes with a
large number of `components`_ that provide bindings to different protocols and
APIs. The `camel-extra`_ project provides further components.

.. _Apache Camel: http://camel.apache.org/
.. _Apache Camel article: http://architects.dzone.com/articles/apache-camel-integration
.. _components: http://camel.apache.org/components.html
.. _camel-extra: http://code.google.com/p/camel-extra/

Usage of Camel's integration components in Akka is essentially a
one-liner. Here's an example.

.. code-block:: scala

   import akka.actor.Actor
   import akka.actor.Actor._
   import akka.camel.{Message, Consumer}

   class MyActor extends Actor with Consumer {
     def endpointUri = "mina:tcp://localhost:6200?textline=true"

     def receive = {
       case msg: Message => { /* ... */}
       case _            => { /* ... */}
     }
   }

   // start and expose actor via tcp
   val myActor = actorOf(Props[MyActor])

The above example exposes an actor over a tcp endpoint on port 6200 via Apache
Camel's `Mina component`_. The actor implements the endpointUri method to define
an endpoint from which it can receive messages. After starting the actor, tcp
clients can immediately send messages to and receive responses from that
actor. If the message exchange should go over HTTP (via Camel's `Jetty
component`_), only the actor's endpointUri method must be changed.

.. _Mina component: http://camel.apache.org/mina.html
.. _Jetty component: http://camel.apache.org/jetty.html

.. code-block:: scala

   class MyActor extends Actor with Consumer {
     def endpointUri = "jetty:http://localhost:8877/example"

     def receive = {
       case msg: Message => { /* ... */}
       case _            => { /* ... */}
     }
   }

Actors can also trigger message exchanges with external systems i.e. produce to
Camel endpoints.

.. code-block:: scala

   import akka.actor.Actor
   import akka.camel.{Producer, Oneway}

   class MyActor extends Actor with Producer with Oneway {
     def endpointUri = "jms:queue:example"
   }

In the above example, any message sent to this actor will be added (produced) to
the example JMS queue. Producer actors may choose from the same set of Camel
components as Consumer actors do.

The number of Camel components is constantly increasing. The akka-camel module
can support these in a plug-and-play manner. Just add them to your application's
classpath, define a component-specific endpoint URI and use it to exchange
messages over the component-specific protocols or APIs. This is possible because
Camel components bind protocol-specific message formats to a Camel-specific
`normalized message format`__. The normalized message format hides
protocol-specific details from Akka and makes it therefore very easy to support
a large number of protocols through a uniform Camel component interface. The
akka-camel module further converts mutable Camel messages into `immutable
representations`__ which are used by Consumer and Producer actors for pattern
matching, transformation, serialization or storage, for example.

__ https://svn.apache.org/repos/asf/camel/trunk/camel-core/src/main/java/org/apache/camel/Message.java
__ http://github.com/jboner/akka/blob/v0.8/akka-camel/src/main/scala/akka/Message.scala#L17


Dependencies
============

Akka's Camel Integration consists of two modules

* akka-camel - this module depends on akka-actor and camel-core (+ transitive
  dependencies) and implements the Camel integration for (untyped) actors

* akka-camel-typed - this module depends on akka-typed-actor and akka-camel (+
  transitive dependencies) and implements the Camel integration for typed actors

The akka-camel-typed module is optional. To have both untyped and typed actors
working with Camel, add the following dependencies to your SBT project
definition.

.. code-block:: scala

   import sbt._

   class Project(info: ProjectInfo) extends DefaultProject(info) with AkkaProject {
     // ...
     val akkaCamel = akkaModule("camel")
     val akkaCamelTyped = akkaModule("camel-typed") // optional typed actor support
     // ...
   }


.. _camel-consume-messages:

Consume messages
================

Actors (untyped)
----------------

For actors (Scala) to receive messages, they must mixin the `Consumer`_
trait. For example, the following actor class (Consumer1) implements the
endpointUri method, which is declared in the Consumer trait, in order to receive
messages from the ``file:data/input/actor`` Camel endpoint. Untyped actors
(Java) need to extend the abstract UntypedConsumerActor class and implement the
getEndpointUri() and onReceive(Object) methods.

.. _Consumer: http://github.com/jboner/akka/blob/master/akka-camel/src/main/scala/akka/camel/Consumer.scala

**Scala**

.. code-block:: scala

   import akka.actor.Actor
   import akka.camel.{Message, Consumer}

   class Consumer1 extends Actor with Consumer {
     def endpointUri = "file:data/input/actor"

     def receive = {
       case msg: Message => println("received %s" format msg.bodyAs[String])
     }
   }

**Java**

.. code-block:: java

   import akka.camel.Message;
   import akka.camel.UntypedConsumerActor;

   public class Consumer1 extends UntypedConsumerActor {
     public String getEndpointUri() {
       return "file:data/input/actor";
     }

     public void onReceive(Object message) {
       Message msg = (Message)message;
       String body = msg.getBodyAs(String.class);
       System.out.println(String.format("received %s", body))
     }
   }

Whenever a file is put into the data/input/actor directory, its content is
picked up by the Camel `file component`_ and sent as message to the
actor. Messages consumed by actors from Camel endpoints are of type
`Message`_. These are immutable representations of Camel messages.

.. _file component: http://camel.apache.org/file2.html
.. _Message: http://github.com/jboner/akka/blob/master/akka-camel/src/main/scala/akka/camel/Message.scala

For Message usage examples refer to the unit tests:

* Message unit tests - `Scala API <http://github.com/jboner/akka/blob/master/akka-camel/src/test/scala/akka/MessageScalaTest.scala>`_
* Message unit tests - `Java API <http://github.com/jboner/akka/blob/master/akka-camel/src/test/java/akka/camel/MessageJavaTestBase.java>`_

Here's another example that sets the endpointUri to
``jetty:http://localhost:8877/camel/default``. It causes Camel's `Jetty
component`_ to start an embedded `Jetty`_ server, accepting HTTP connections
from localhost on port 8877.

.. _Jetty component: http://camel.apache.org/jetty.html
.. _Jetty: http://www.eclipse.org/jetty/

**Scala**

.. code-block:: scala

   import akka.actor.Actor
   import akka.camel.{Message, Consumer}

   class Consumer2 extends Actor with Consumer {
     def endpointUri = "jetty:http://localhost:8877/camel/default"

     def receive = {
       case msg: Message => self.reply("Hello %s" format msg.bodyAs[String])
     }
   }

**Java**

.. code-block:: java

   import akka.camel.Message;
   import akka.camel.UntypedConsumerActor;

   public class Consumer2 extends UntypedConsumerActor {
     public String getEndpointUri() {
       return "jetty:http://localhost:8877/camel/default";
     }

     public void onReceive(Object message) {
       Message msg = (Message)message;
       String body = msg.getBodyAs(String.class);
       getContext().tryReply(String.format("Hello %s", body));
     }
   }

After starting the actor, clients can send messages to that actor by POSTing to
``http://localhost:8877/camel/default``. The actor sends a response by using the
self.reply method (Scala). For returning a message body and headers to the HTTP
client the response type should be `Message`_. For any other response type, a
new Message object is created by akka-camel with the actor response as message
body.

.. _Message: http://github.com/jboner/akka/blob/master/akka-camel/src/main/scala/akka/camel/Message.scala


Typed actors
------------

Typed actors can also receive messages from Camel endpoints. In contrast to
(untyped) actors, which only implement a single receive or onReceive method, a
typed actor may define several (message processing) methods, each of which can
receive messages from a different Camel endpoint. For a typed actor method to be
exposed as Camel endpoint it must be annotated with the `@consume
annotation`_. For example, the following typed consumer actor defines two
methods, foo and bar.

.. _@consume annotation: http://github.com/jboner/akka/blob/master/akka-camel/src/main/java/akka/camel/consume.java

**Scala**

.. code-block:: scala

   import org.apache.camel.{Body, Header}
   import akka.actor.TypedActor
   import akka.camel.consume

   trait TypedConsumer1 {
     @consume("file:data/input/foo")
     def foo(body: String): Unit

     @consume("jetty:http://localhost:8877/camel/bar")
     def bar(@Body body: String, @Header("X-Whatever") header: String): String
   }

   class TypedConsumer1Impl extends TypedActor with TypedConsumer1 {
     def foo(body: String) = println("Received message: %s" format body)
     def bar(body: String, header: String) = "body=%s header=%s" format (body, header)
   }

**Java**

.. code-block:: java

   import org.apache.camel.Body;
   import org.apache.camel.Header;
   import akka.actor.TypedActor;
   import akka.camel.consume;

   public interface TypedConsumer1 {
     @consume("file:data/input/foo")
     public void foo(String body);

     @consume("jetty:http://localhost:8877/camel/bar")
     public String bar(@Body String body, @Header("X-Whatever") String header);
   }

   public class TypedConsumer1Impl extends TypedActor implements TypedConsumer1 {
     public void foo(String body) {
       System.out.println(String.format("Received message: ", body));
     }

     public String bar(String body, String header) {
       return String.format("body=%s header=%s", body, header);
     }
   }

The foo method can be invoked by placing a file in the data/input/foo
directory. Camel picks up the file from this directory and akka-camel invokes
foo with the file content as argument (converted to a String). Camel
automatically tries to convert messages to appropriate types as defined by the
method parameter(s). The conversion rules are described in detail on the
following pages:

* `Bean integration <http://camel.apache.org/bean-integration.html>`_
* `Bean binding <http://camel.apache.org/bean-binding.html>`_
* `Parameter binding <http://camel.apache.org/parameter-binding-annotations.html>`_

The bar method can be invoked by POSTing a message to
http://localhost:8877/camel/bar. Here, parameter binding annotations are used to
tell Camel how to extract data from the HTTP message. The @Body annotation binds
the HTTP request body to the first parameter, the @Header annotation binds the
X-Whatever header to the second parameter. The return value is sent as HTTP
response message body to the client.

Parameter binding annotations must be placed on the interface, the @consume
annotation can also be placed on the methods in the implementation class.


.. _camel-publishing:

Consumer publishing
-------------------

Actors (untyped)
^^^^^^^^^^^^^^^^

Publishing a consumer actor at its Camel endpoint occurs when the actor is
started. Publication is done asynchronously; setting up an endpoint (more
precisely, the route from that endpoint to the actor) may still be in progress
after the ActorRef method returned.

**Scala**

.. code-block:: scala

   import akka.actor.Actor._

   val actor = actorOf(Props[Consumer1]) // create Consumer actor and activate endpoint in background

**Java**

.. code-block:: java

   import static akka.actor.Actors.*;
   import akka.actor.ActorRef;

   ActorRef actor = actorOf(new Props(Consumer1.class)); // create Consumer actor and activate endpoint in background


Typed actors
^^^^^^^^^^^^

Publishing of typed actor methods is done when the typed actor is created with
one of the TypedActor.newInstance(..) methods. Publication is done in the
background here as well i.e. it may still be in progress when
TypedActor.newInstance(..) returns.

**Scala**

.. code-block:: scala

   import akka.actor.TypedActor

   // create TypedConsumer1 object and activate endpoint(s) in background
   val consumer = TypedActor.newInstance(classOf[TypedConsumer1], classOf[TypedConumer1Impl])

**Java**

.. code-block:: java

   import akka.actor.TypedActor;

   // create TypedConsumer1 object and activate endpoint(s) in background
   TypedConsumer1 consumer = TypedActor.newInstance(TypedConsumer1.class, TypedConumer1Impl.class);


.. _camel-consumers-and-camel-service:

Consumers and the CamelService
------------------------------

Publishing of consumer actors or typed actor methods requires a running
CamelService. The Akka :ref:`microkernel` can start a CamelService automatically
(see :ref:`camel-configuration`). When using Akka in other environments, a
CamelService must be started manually. Applications can do that by calling the
CamelServiceManager.startCamelService method.

**Scala**

.. code-block:: scala

   import akka.camel.CamelServiceManager._

   startCamelService

**Java**

.. code-block:: java

   import static akka.camel.CamelServiceManager.*;

   startCamelService();

If applications need to wait for a certain number of consumer actors or typed
actor methods to be published they can do so with the
``CamelServiceManager.mandatoryService.awaitEndpointActivation`` method, where
``CamelServiceManager.mandatoryService`` is the current CamelService instance
(or throws an IllegalStateException there's no current CamelService).

**Scala**

.. code-block:: scala

   import akka.camel.CamelServiceManager._

   startCamelService

   // Wait for three conumer endpoints to be activated
   mandatoryService.awaitEndpointActivation(3) {
     // Start three consumer actors (for example)
     // ...
   }

   // Communicate with consumer actors via their activated endpoints
   // ...

**Java**

.. code-block:: java

   import akka.japi.SideEffect;
   import static akka.camel.CamelServiceManager.*;

   startCamelService();

   // Wait for three conumer endpoints to be activated
   getMandatoryService().awaitEndpointActivation(3, new SideEffect() {
     public void apply() {
       // Start three consumer actors (for example)
       // ...
     }
   });

   // Communicate with consumer actors via their activated endpoints
   // ...

Alternatively, one can also use ``Option[CamelService]`` returned by
``CamelServiceManager.service``.

**Scala**

.. code-block:: scala

   import akka.camel.CamelServiceManager._

   startCamelService

   for(s <- service) s.awaitEndpointActivation(3) {
     // ...
   }

**Java**

.. code-block:: java

   import java.util.concurrent.CountDownLatch;

   import akka.camel.CamelService;
   import static akka.camel.CamelServiceManager.*;

   startCamelService();

   for (CamelService s : getService()) s.awaitEndpointActivation(3, new SideEffect() {
     public void apply() {
       // ...
     }
   });

:ref:`camel-configuration` additionally describes how a CamelContext, that is
managed by a CamelService, can be cutomized before starting the service. When
the CamelService is no longer needed, it should be stopped.

**Scala**

.. code-block:: scala

   import akka.camel.CamelServiceManager._

   stopCamelService

**Java**

.. code-block:: java

   import static akka.camel.CamelServiceManager.*;

   stopCamelService();


.. _camel-unpublishing:

Consumer un-publishing
----------------------

Actors (untyped)
^^^^^^^^^^^^^^^^

When an actor is stopped, the route from the endpoint to that actor is stopped
as well. For example, stopping an actor that has been previously published at
``http://localhost:8877/camel/test`` will cause a connection failure when trying
to access that endpoint. Stopping the route is done asynchronously; it may be
still in progress after the ``ActorRef.stop`` method returned.

**Scala**

.. code-block:: scala

   import akka.actor.Actor._

   val actor = actorOf(Props[Consumer1]) // create Consumer actor
   actor                    // activate endpoint in background
   // ...
   actor.stop                     // deactivate endpoint in background

**Java**

.. code-block:: java

   import static akka.actor.Actors.*;
   import akka.actor.ActorRef;

   ActorRef actor = actorOf(new Props(Consumer1.class)); // create Consumer actor and activate endpoint in background
   // ...
   actor.stop();                              // deactivate endpoint in background


Typed actors
^^^^^^^^^^^^

When a typed actor is stopped, routes to @consume annotated methods of this
typed actors are stopped as well. Stopping the routes is done asynchronously; it
may be still in progress after the TypedActor.stop method returned.

**Scala**

.. code-block:: scala

   import akka.actor.TypedActor

   // create TypedConsumer1 object and activate endpoint(s) in background
   val consumer = TypedActor.newInstance(classOf[TypedConsumer1], classOf[TypedConumer1Impl])

   // deactivate endpoints in background
   TypedActor.stop(consumer)

**Java**

.. code-block:: java

   import akka.actor.TypedActor;

   // Create typed consumer actor and activate endpoints in background
   TypedConsumer1 consumer = TypedActor.newInstance(TypedConsumer1.class, TypedConumer1Impl.class);

   // Deactivate endpoints in background
   TypedActor.stop(consumer);


.. _camel-acknowledgements:

Acknowledgements
----------------

Actors (untyped)
^^^^^^^^^^^^^^^^

With in-out message exchanges, clients usually know that a message exchange is
done when they receive a reply from a consumer actor. The reply message can be a
Message (or any object which is then internally converted to a Message) on
success, and a Failure message on failure.

With in-only message exchanges, by default, an exchange is done when a message
is added to the consumer actor's mailbox. Any failure or exception that occurs
during processing of that message by the consumer actor cannot be reported back
to the endpoint in this case. To allow consumer actors to positively or
negatively acknowledge the receipt of a message from an in-only message
exchange, they need to override the ``autoack`` (Scala) or ``isAutoack`` (Java)
method to return false. In this case, consumer actors must reply either with a
special Ack message (positive acknowledgement) or a Failure (negative
acknowledgement).

**Scala**

.. code-block:: scala

   import akka.camel.{Ack, Failure}
   // ... other imports omitted

   class Consumer3 extends Actor with Consumer {
     override def autoack = false

     def endpointUri = "jms:queue:test"

     def receive = {
       // ...
       self.reply(Ack) // on success
       // ...
       self.reply(Failure(...)) // on failure
     }
   }

**Java**

.. code-block:: java

   import akka.camel.Failure
   import static akka.camel.Ack.ack;
   // ... other imports omitted

   public class Consumer3 extends UntypedConsumerActor {

     public String getEndpointUri() {
       return "jms:queue:test";
     }

     public boolean isAutoack() {
       return false;
     }

     public void onReceive(Object message) {
       // ...
       getContext().reply(ack()) // on success
       // ...
       val e: Exception = ...
       getContext().reply(new Failure(e)) // on failure
     }
   }


.. _camel-blocking-exchanges:

Blocking exchanges
------------------

By default, message exchanges between a Camel endpoint and a consumer actor are
non-blocking because, internally, the ! (bang) operator is used to commicate
with the actor. The route to the actor does not block waiting for a reply. The
reply is sent asynchronously (see also :ref:`camel-asynchronous-routing`).
Consumer actors however can be configured to make this interaction blocking.

**Scala**

.. code-block:: scala

   class ExampleConsumer extends Actor with Consumer {
     override def blocking = true

     def endpointUri = ...
     def receive = {
       // ...
     }
   }

**Java**

.. code-block:: java

   public class ExampleConsumer extends UntypedConsumerActor {

     public boolean isBlocking() {
       return true;
     }

     public String getEndpointUri() {
       // ...
     }

     public void onReceive(Object message) {
       // ...
     }
   }

In this case, the ``!!`` (bangbang) operator is used internally to communicate
with the actor which blocks a thread until the consumer sends a response or
throws an exception within receive. Although it may decrease scalability, this
setting can simplify error handling (see `this article`_) or allows timeout
configurations on actor-level (see :ref:`camel-timeout`).

.. _this article: http://krasserm.blogspot.com/2011/02/akka-consumer-actors-new-features-and.html


.. _camel-timeout:

Consumer timeout
----------------

Endpoints that support two-way communications need to wait for a response from
an (untyped) actor or typed actor before returning it to the initiating client.
For some endpoint types, timeout values can be defined in an endpoint-specific
way which is described in the documentation of the individual `Camel
components`_. Another option is to configure timeouts on the level of consumer
actors and typed consumer actors.

.. _Camel components: http://camel.apache.org/components.html


Typed actors
^^^^^^^^^^^^

For typed actors, timeout values for method calls that return a result can be
set when the typed actor is created. In the following example, the timeout is
set to 20 seconds (default is 5 seconds).

**Scala**

.. code-block:: scala

   import akka.actor.TypedActor

   val consumer = TypedActor.newInstance(classOf[TypedConsumer1], classOf[TypedConumer1Impl], 20000 /* 20 seconds */)

**Java**

.. code-block:: java

   import akka.actor.TypedActor;

   TypedConsumer1 consumer = TypedActor.newInstance(TypedConsumer1.class, TypedConumer1Impl.class, 20000 /* 20 seconds */);


Actors (untyped)
^^^^^^^^^^^^^^^^

Two-way communications between a Camel endpoint and an (untyped) actor are
initiated by sending the request message to the actor with the ``!`` (bang)
operator and the actor replies to the endpoint when the response is ready. In
order to support timeouts on actor-level, endpoints need to send the request
message with the ``!!`` (bangbang) operator for which a timeout value is
applicable. This can be achieved by overriding the Consumer.blocking method to
return true.

**Scala**

.. code-block:: scala

   class Consumer2 extends Actor with Consumer {
     self.timeout = 20000 // timeout set to 20 seconds

     override def blocking = true

     def endpointUri = "direct:example"

     def receive = {
       // ...
     }
   }

**Java**

.. code-block:: java

   public class Consumer2 extends UntypedConsumerActor {

     public Consumer2() {
       getContext().setTimeout(20000); // timeout set to 20 seconds
     }

     public String getEndpointUri() {
       return "direct:example";
     }

     public boolean isBlocking() {
       return true;
     }

     public void onReceive(Object message) {
       // ...
     }
   }

This is a valid approach for all endpoint types that do not "natively" support
asynchronous two-way message exchanges. For all other endpoint types (like
`Jetty`_ endpoints) is it not recommended to switch to blocking mode but rather
to configure timeouts in an endpoint-specific way (see
also :ref:`camel-asynchronous-routing`).


Remote consumers
----------------

Actors (untyped)
^^^^^^^^^^^^^^^^

Publishing of remote consumer actors is always done on the server side, local
proxies are never published. Hence the CamelService must be started on the
remote node. For example, to publish an (untyped) actor on a remote node at
endpoint URI ``jetty:http://localhost:6644/remote-actor-1``, define the
following consumer actor class.

**Scala**

.. code-block:: scala

   import akka.actor.Actor
   import akka.annotation.consume
   import akka.camel.Consumer

   class RemoteActor1 extends Actor with Consumer {
     def endpointUri = "jetty:http://localhost:6644/remote-actor-1"

     protected def receive = {
       case msg => self.reply("response from remote actor 1")
     }
   }

**Java**

.. code-block:: java

   import akka.camel.UntypedConsumerActor;

   public class RemoteActor1 extends UntypedConsumerActor {
     public String getEndpointUri() {
       return "jetty:http://localhost:6644/remote-actor-1";
     }

     public void onReceive(Object message) {
       getContext().tryReply("response from remote actor 1");
     }
   }

On the remote node, start a `CamelService`_, start a remote server, create the
actor and register it at the remote server.

.. _CamelService: http://github.com/jboner/akka/blob/master/akka-camel/src/main/scala/akka/camel/CamelService.scala

**Scala**

.. code-block:: scala

   import akka.camel.CamelServiceManager._
   import akka.actor.Actor._
   import akka.actor.ActorRef

   // ...
   startCamelService

   val consumer = val consumer = actorOf(Props[RemoteActor1])

   remote.start("localhost", 7777)
   remote.register(consumer) // register and start remote consumer
   // ...

**Java**

.. code-block:: java

   import akka.camel.CamelServiceManager;
   import static akka.actor.Actors.*;

   // ...
   CamelServiceManager.startCamelService();

   ActorRef actor = actorOf(new Props(RemoteActor1.class));

   remote().start("localhost", 7777);
   remote().register(actor); // register and start remote consumer
   // ...

Explicitly starting a CamelService can be omitted when Akka is running in Kernel
mode, for example (see also :ref:`camel-configuration`).


Typed actors
^^^^^^^^^^^^

Remote typed consumer actors can be registered with one of the
``registerTyped*`` methods on the remote server. The following example registers
the actor with the custom id "123".

**Scala**

.. code-block:: scala

   import akka.actor.TypedActor

   // ...
   val obj = TypedActor.newRemoteInstance(
     classOf[SampleRemoteTypedConsumer],
     classOf[SampleRemoteTypedConsumerImpl])

   remote.registerTypedActor("123", obj)
   // ...

**Java**

.. code-block:: java

   import akka.actor.TypedActor;

   SampleRemoteTypedConsumer obj = (SampleRemoteTypedConsumer)TypedActor.newInstance(
     SampleRemoteTypedConsumer.class,
     SampleRemoteTypedConsumerImpl.class);

   remote.registerTypedActor("123", obj)
   // ...


Produce messages
================

A minimum pre-requisite for producing messages to Camel endpoints with producer
actors (see below) is an initialized and started CamelContextManager.

**Scala**

.. code-block:: scala

   import akka.camel.CamelContextManager

   CamelContextManager.init  // optionally takes a CamelContext as argument
   CamelContextManager.start // starts the managed CamelContext

**Java**

.. code-block:: java

   import akka.camel.CamelContextManager;

   CamelContextManager.init();  // optionally takes a CamelContext as argument
   CamelContextManager; // starts the managed CamelContext

For using producer actors, application may also start a CamelService. This will
not only setup a CamelContextManager behind the scenes but also register
listeners at the actor registry (needed to publish consumer actors). If your
application uses producer actors only and you don't want to have the (very
small) overhead generated by the registry listeners then setting up a
CamelContextManager without starting CamelService is recommended. Otherwise,
just start a CamelService as described for consumer
actors: :ref:`camel-consumers-and-camel-service`.


Producer trait
--------------

Actors (untyped)
^^^^^^^^^^^^^^^^

For sending messages to Camel endpoints, actors

* written in Scala need to mixin the `Producer`_ trait and implement the
  endpointUri method.

* written in Java need to extend the abstract UntypedProducerActor class and
  implement the getEndpointUri() method. By extending the UntypedProducerActor
  class, untyped actors (Java) inherit the behaviour of the Producer trait.

.. _Producer: http://github.com/jboner/akka/blob/master/akka-camel/src/main/scala/akka/camel/Producer.scala

**Scala**

.. code-block:: scala

   import akka.actor.Actor
   import akka.camel.Producer

   class Producer1 extends Actor with Producer {
     def endpointUri = "http://localhost:8080/news"
   }

**Java**

.. code-block:: java

   import akka.camel.UntypedProducerActor;

   public class Producer1 extends UntypedProducerActor {
     public String getEndpointUri() {
       return "http://localhost:8080/news";
     }
   }

Producer1 inherits a default implementation of the receive method from the
Producer trait. To customize a producer actor's default behavior it is
recommended to override the Producer.receiveBeforeProduce and
Producer.receiveAfterProduce methods. This is explained later in more detail.
Actors should not override the default Producer.receive method.

Any message sent to a Producer actor (or UntypedProducerActor) will be sent to
the associated Camel endpoint, in the above example to
``http://localhost:8080/news``. Response messages (if supported by the
configured endpoint) will, by default, be returned to the original sender. The
following example uses the ``?`` operator (Scala) to send a message to a
Producer actor and waits for a response. In Java, the sendRequestReply method is
used.

**Scala**

.. code-block:: scala

   import akka.actor.Actor._
   import akka.actor.ActorRef

   val producer = actorOf(Props[Producer1])
   val response = (producer ? "akka rocks").get
   val body = response.bodyAs[String]

**Java**

.. code-block:: java

   import akka.actor.ActorRef;
   import static akka.actor.Actors.*;
   import akka.camel.Message;

   ActorRef producer = actorOf(new Props(Producer1.class));
   Message response = (Message)producer.sendRequestReply("akka rocks");
   String body = response.getBodyAs(String.class)

If the message is sent using the ! operator (or the tell method in Java)
then the response message is sent back asynchronously to the original sender. In
the following example, a Sender actor sends a message (a String) to a producer
actor using the ! operator and asynchronously receives a response (of type
Message).

**Scala**

.. code-block:: scala

   import akka.actor.{Actor, ActorRef}
   import akka.camel.Message

   class Sender(producer: ActorRef) extends Actor {
     def receive = {
       case request: String   => producer ! request
       case response: Message => {
         /* process response ... */
       }
       // ...
     }
   }

**Java**

.. code-block:: java

   // TODO


.. _camel-custom-processing:

Custom Processing
^^^^^^^^^^^^^^^^^

Instead of replying to the initial sender, producer actors can implement custom
reponse processing by overriding the receiveAfterProduce method (Scala) or
onReceiveAfterProduce method (Java). In the following example, the reponse
message is forwarded to a target actor instead of being replied to the original
sender.

**Scala**

.. code-block:: scala

   import akka.actor.{Actor, ActorRef}
   import akka.camel.Producer

   class Producer1(target: ActorRef) extends Actor with Producer {
     def endpointUri = "http://localhost:8080/news"

     override protected def receiveAfterProduce = {
       // do not reply but forward result to target
       case msg => target forward msg
     }
   }

**Java**

.. code-block:: java

   import akka.actor.ActorRef;
   import akka.camel.UntypedProducerActor;

   public class Producer1 extends UntypedProducerActor {
       private ActorRef target;

       public Producer1(ActorRef target) {
           this.target = target;
       }

       public String getEndpointUri() {
           return "http://localhost:8080/news";
       }

       @Override
       public void onReceiveAfterProduce(Object message) {
           target.forward((Message)message, getContext());
       }
   }

To create an untyped actor instance with a constructor argument, a factory is
needed (this should be doable without a factory in upcoming Akka versions).

.. code-block:: java

   import akka.actor.ActorRef;
   import akka.actor.UntypedActorFactory;
   import akka.actor.UntypedActor;

   public class Producer1Factory implements UntypedActorFactory {

       private ActorRef target;

       public Producer1Factory(ActorRef target) {
           this.target = target;
       }

       public UntypedActor create() {
           return new Producer1(target);
       }
   }

The instanitation is done with the Actors.actorOf method and the factory as
argument.

.. code-block:: java

   import static akka.actor.Actors.*;
   import akka.actor.ActorRef;

   ActorRef target = ...
   ActorRef producer = actorOf(Props(new Producer1Factory(target)));
   producer;

Before producing messages to endpoints, producer actors can pre-process them by
overriding the receiveBeforeProduce method (Scala) or onReceiveBeforeProduce
method (Java).

**Scala**

.. code-block:: scala

   import akka.actor.{Actor, ActorRef}
   import akka.camel.{Message, Producer}

   class Producer1(target: ActorRef) extends Actor with Producer {
     def endpointUri = "http://localhost:8080/news"

     override protected def receiveBeforeProduce = {
       case msg: Message => {
         // do some pre-processing (e.g. add endpoint-specific message headers)
         // ...

         // and return the modified message
         msg
       }
     }
   }

**Java**

.. code-block:: java

   import akka.actor.ActorRef;
   import akka.camel.Message
   import akka.camel.UntypedProducerActor;

   public class Producer1 extends UntypedProducerActor {
       private ActorRef target;

       public Producer1(ActorRef target) {
           this.target = target;
       }

       public String getEndpointUri() {
           return "http://localhost:8080/news";
       }

       @Override
       public Object onReceiveBeforeProduce(Object message) {
           Message msg = (Message)message;
           // do some pre-processing (e.g. add endpoint-specific message headers)
           // ...

           // and return the modified message
           return msg
       }
   }


Producer configuration options
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The interaction of producer actors with Camel endpoints can be configured to be
one-way or two-way (by initiating in-only or in-out message exchanges,
respectively). By default, the producer initiates an in-out message exchange
with the endpoint. For initiating an in-only exchange, producer actors

* written in Scala either have to override the oneway method to return true
* written in Java have to override the isOneway method to return true.

**Scala**

.. code-block:: scala

   import akka.camel.Producer

   class Producer2 extends Actor with Producer {
     def endpointUri = "jms:queue:test"
     override def oneway = true
   }

**Java**

.. code-block:: java

   import akka.camel.UntypedProducerActor;

   public class SampleUntypedReplyingProducer extends UntypedProducerActor {
       public String getEndpointUri() {
           return "jms:queue:test";
       }

       @Override
       public boolean isOneway() {
           return true;
       }
   }

Message correlation
^^^^^^^^^^^^^^^^^^^

To correlate request with response messages, applications can set the
Message.MessageExchangeId message header.

**Scala**

.. code-block:: scala

   import akka.camel.Message

   producer ! Message("bar", Map(Message.MessageExchangeId -> "123"))

**Java**

.. code-block:: java

   // TODO

Responses of type Message or Failure will contain that header as well. When
receiving messages from Camel endpoints this message header is already set (see
:ref:`camel-consume-messages`).


Matching responses
^^^^^^^^^^^^^^^^^^

The following code snippet shows how to best match responses when sending
messages with the ``?`` operator (Scala) or with the ``ask`` method
(Java).

**Scala**

.. code-block:: scala

   val response = (producer ? message).get

   response match {
     case Some(Message(body, headers)) => ...
     case Some(Failure(exception, headers)) => ...
     case _ => ...
   }

**Java**

.. code-block:: java

   // TODO


ProducerTemplate
----------------

The `Producer`_ trait (and the abstract UntypedProducerActor class) is a very
convenient way for actors to produce messages to Camel endpoints. (Untyped)
actors and typed actors may also use a Camel `ProducerTemplate`_ for producing
messages to endpoints. For typed actors it's the only way to produce messages to
Camel endpoints.

At the moment, only the Producer trait fully supports asynchronous in-out
message exchanges with Camel endpoints without allocating a thread for the full
duration of the exchange. For example, when using endpoints that support
asynchronous message exchanges (such as Jetty endpoints that internally use
`Jetty's asynchronous HTTP client`_) then usage of the Producer trait is highly
recommended (see also :ref:`camel-asynchronous-routing`).

.. _Producer: http://github.com/jboner/akka/blob/master/akka-camel/src/main/scala/akka/camel/Producer.scala
.. _ProducerTemplate: http://camel.apache.org/maven/camel-2.2.0/camel-core/apidocs/index.html
.. _Jetty's asynchronous HTTP client: http://wiki.eclipse.org/Jetty/Tutorial/HttpClient


Actors (untyped)
^^^^^^^^^^^^^^^^

A managed ProducerTemplate instance can be obtained via
CamelContextManager.mandatoryTemplate. In the following example, an actor uses a
ProducerTemplate to send a one-way message to a ``direct:news`` endpoint.

**Scala**

.. code-block:: scala

   import akka.actor.Actor
   import akka.camel.CamelContextManager

   class ProducerActor extends Actor {
     protected def receive = {
       // one-way message exchange with direct:news endpoint
       case msg => CamelContextManager.mandatoryTemplate.sendBody("direct:news", msg)
     }
   }

**Java**

.. code-block:: java

   import akka.actor.UntypedActor;
   import akka.camel.CamelContextManager;

   public class SampleUntypedActor extends UntypedActor {
       public void onReceive(Object msg) {
           CamelContextManager.getMandatoryTemplate().sendBody("direct:news", msg);
       }
   }

Alternatively, one can also use ``Option[ProducerTemplate]`` returned by
``CamelContextManager.template``.

**Scala**

.. code-block:: scala

   import akka.actor.Actor
   import akka.camel.CamelContextManager

   class ProducerActor extends Actor {
     protected def receive = {
       // one-way message exchange with direct:news endpoint
       case msg => for(t <- CamelContextManager.template) t.sendBody("direct:news", msg)
     }
   }

**Java**

.. code-block:: java

   import org.apache.camel.ProducerTemplate

   import akka.actor.UntypedActor;
   import akka.camel.CamelContextManager;

   public class SampleUntypedActor extends UntypedActor {
       public void onReceive(Object msg) {
           for (ProducerTemplate t : CamelContextManager.getTemplate()) {
               t.sendBody("direct:news", msg);
           }
       }
   }

For initiating a a two-way message exchange, one of the
``ProducerTemplate.request*`` methods must be used.

**Scala**

.. code-block:: scala

   import akka.actor.Actor
   import akka.camel.CamelContextManager

   class ProducerActor extends Actor {
     protected def receive = {
       // two-way message exchange with direct:news endpoint
       case msg => self.reply(CamelContextManager.mandatoryTemplate.requestBody("direct:news", msg))
     }
   }

**Java**

.. code-block:: java

   import akka.actor.UntypedActor;
   import akka.camel.CamelContextManager;

   public class SampleUntypedActor extends UntypedActor {
       public void onReceive(Object msg) {
           getContext().tryReply(CamelContextManager.getMandatoryTemplate().requestBody("direct:news", msg));
       }
   }


Typed actors
^^^^^^^^^^^^

Typed Actors get access to a managed ProducerTemplate in the same way, as shown
in the next example.

**Scala**

.. code-block:: scala

   // TODO

**Java**

.. code-block:: java

   import akka.actor.TypedActor;
   import akka.camel.CamelContextManager;

   public class SampleProducerImpl extends TypedActor implements SampleProducer {
       public void foo(String msg) {
           ProducerTemplate template = CamelContextManager.getMandatoryTemplate();
           template.sendBody("direct:news", msg);
       }
   }


.. _camel-asynchronous-routing:

Asynchronous routing
====================

Since Akka 0.10, in-out message exchanges between endpoints and actors are
designed to be asynchronous. This is the case for both, consumer and producer
actors.

* A consumer endpoint sends request messages to its consumer actor using the ``!``
  (bang) operator and the actor returns responses with self.reply once they are
  ready. The sender reference used for reply is an adapter to Camel's asynchronous
  routing engine that implements the ActorRef trait.

* A producer actor sends request messages to its endpoint using Camel's
  asynchronous routing engine. Asynchronous responses are wrapped and added to the
  producer actor's mailbox for later processing. By default, response messages are
  returned to the initial sender but this can be overridden by Producer
  implementations (see also description of the ``receiveAfterProcessing`` method
  in :ref:`camel-custom-processing`).

However, asynchronous two-way message exchanges, without allocating a thread for
the full duration of exchange, cannot be generically supported by Camel's
asynchronous routing engine alone. This must be supported by the individual
`Camel components`_ (from which endpoints are created) as well. They must be
able to suspend any work started for request processing (thereby freeing threads
to do other work) and resume processing when the response is ready. This is
currently the case for a `subset of components`_ such as the `Jetty component`_.
All other Camel components can still be used, of course, but they will cause
allocation of a thread for the duration of an in-out message exchange. There's
also a :ref:`camel-async-example` that implements both, an asynchronous
consumer and an asynchronous producer, with the jetty component.

.. _Camel components: http://camel.apache.org/components.html
.. _subset of components: http://camel.apache.org/asynchronous-routing-engine.html
.. _Jetty component: http://camel.apache.org/jetty.html


Fault tolerance
===============

Consumer actors and typed actors can be also managed by supervisors. If a
consumer is configured to be restarted upon failure the associated Camel
endpoint is not restarted. It's behaviour during restart is as follows.

* A one-way (in-only) message exchange will be queued by the consumer and
  processed once restart completes.

* A two-way (in-out) message exchange will wait and either succeed after restart
  completes or time-out when the restart duration exceeds
  the :ref:`camel-timeout`.

If a consumer is configured to be shut down upon failure, the associated
endpoint is shut down as well. For details refer to :ref:`camel-unpublishing`.

For examples, tips and trick how to implement fault-tolerant consumer and
producer actors, take a look at these two articles.

* `Akka Consumer Actors: New Features and Best Practices <http://krasserm.blogspot.com/2011/02/akka-consumer-actors-new-features-and.html>`_
* `Akka Producer Actors: New Features and Best Practices <http://krasserm.blogspot.com/2011/02/akka-producer-actor-new-features-and.html>`_


.. _camel-configuration:

CamelService configuration
==========================

For publishing consumer actors and typed actor methods
(:ref:`camel-publishing`), applications must start a CamelService. When starting
Akka in :ref:`microkernel` mode then a CamelService can be started automatically
when camel is added to the enabled-modules list in :ref:`configuration`, for example:

.. code-block:: none

   akka {
     ...
     enabled-modules = ["camel"] # Options: ["remote", "camel", "http"]
     ...
   }

Applications that do not use the Akka Kernel, such as standalone applications
for example, need to start a CamelService manually, as explained in the
following subsections.When starting a CamelService manually, settings in
:ref:`configuration` are ignored.


Standalone applications
-----------------------

Standalone application should create and start a CamelService in the following way.

**Scala**

.. code-block:: scala

   import akka.camel.CamelServiceManager._

   startCamelService

**Java**

.. code-block:: java

   import static akka.camel.CamelServiceManager.*;

   startCamelService();

Internally, a CamelService uses the CamelContextManager singleton to manage a
CamelContext. A CamelContext manages the routes from endpoints to consumer
actors and typed actors. These routes are added and removed at runtime (when
(untyped) consumer actors and typed consumer actors are started and stopped).
Applications may additionally want to add their own custom routes or modify the
CamelContext in some other way. This can be done by initializing the
CamelContextManager manually and making modifications to CamelContext **before**
the CamelService is started.

**Scala**

.. code-block:: scala

   import org.apache.camel.builder.RouteBuilder

   import akka.camel.CamelContextManager
   import akka.camel.CamelServiceManager._

   CamelContextManager.init

   // add a custom route to the managed CamelContext
   CamelContextManager.mandatoryContext.addRoutes(new CustomRouteBuilder)

   startCamelService

   // an application-specific route builder
   class CustomRouteBuilder extends RouteBuilder {
     def configure {
       // ...
     }
   }

**Java**

.. code-block:: java

   import org.apache.camel.builder.RouteBuilder;

   import akka.camel.CamelContextManager;
   import static akka.camel.CamelServiceManager.*;

   CamelContextManager.init();

   // add a custom route to the managed CamelContext
   CamelContextManager.getMandatoryContext().addRoutes(new CustomRouteBuilder());

   startCamelService();

   // an application-specific route builder
   private static class CustomRouteBuilder extends RouteBuilder {
       public void configure() {
           // ...
       }
   }


Applications may even provide their own CamelContext instance as argument to the
init method call as shown in the following snippet. Here, a DefaultCamelContext
is created using a Spring application context as `registry`_.

.. _registry: http://camel.apache.org/registry.html


**Scala**

.. code-block:: scala

   import org.apache.camel.impl.DefaultCamelContext
   import org.apache.camel.spring.spi.ApplicationContextRegistry
   import org.springframework.context.support.ClassPathXmlApplicationContext

   import akka.camel.CamelContextManager
   import akka.camel.CamelServiceManager._

   // create a custom Camel registry backed up by a Spring application context
   val context = new ClassPathXmlApplicationContext("/context.xml")
   val registry = new ApplicationContextRegistry(context)

   // initialize CamelContextManager with a DefaultCamelContext using the custom registry
   CamelContextManager.init(new DefaultCamelContext(registry))

   // ...

   startCamelService

**Java**

.. code-block:: java

   import org.apache.camel.impl.DefaultCamelContext
   import org.apache.camel.spi.Registry;
   import org.apache.camel.spring.spi.ApplicationContextRegistry;

   import org.springframework.context.ApplicationContext;
   import org.springframework.context.support.ClassPathXmlApplicationContext;

   import akka.camel.CamelContextManager;
   import static akka.camel.CamelServiceManager.*;

   // create a custom Camel registry backed up by a Spring application context
   ApplicationContext context = new ClassPathXmlApplicationContext("/context.xml");
   Registry registry = new ApplicationContextRegistry(context);

   // initialize CamelContextManager with a DefaultCamelContext using the custom registry
   CamelContextManager.init(new DefaultCamelContext(registry));

   // ...

   startCamelService();


.. _camel-spring-applications:

Standalone Spring applications
------------------------------

A better approach to configure a Spring application context as registry for the
CamelContext is to use `Camel's Spring support`_. Furthermore,
the :ref:`spring-module` module additionally supports a <camel-service> element
for creating and starting a CamelService. An optional reference to a custom
CamelContext can be defined for <camel-service> as well. Here's an example.

.. _Camel's Spring support: http://camel.apache.org/spring.html

.. code-block:: xml

   <!-- context.xml -->

   <beans xmlns="http://www.springframework.org/schema/beans"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:akka="http://www.akka.io/schema/akka"
          xmlns:camel="http://camel.apache.org/schema/spring"
          xsi:schemaLocation="
   http://www.springframework.org/schema/beans
   http://www.springframework.org/schema/beans/spring-beans-2.5.xsd
   http://www.akka.io/schema/akka
   http://akka.io/akka-0.10.xsd
   http://camel.apache.org/schema/spring
   http://camel.apache.org/schema/spring/camel-spring.xsd">

     <!-- A custom CamelContext (SpringCamelContext) -->
     <camel:camelContext id="camelContext">
       <!-- ... -->
     </camel:camelContext>

     <!-- Create a CamelService using a custom CamelContext -->
     <akka:camel-service>
       <akka:camel-context ref="camelContext" />
     </akka:camel-service>

   </beans>

Creating a CamelContext this way automatically adds the defining Spring
application context as registry to that CamelContext. The CamelService is
started when the application context is started and stopped when the application
context is closed. A simple usage example is shown in the following snippet.

**Scala**

.. code-block:: scala

   import org.springframework.context.support.ClassPathXmlApplicationContext
   import akka.camel.CamelContextManager

   // Create and start application context (start CamelService)
   val appctx = new ClassPathXmlApplicationContext("/context.xml")

   // Access to CamelContext (SpringCamelContext)
   val ctx = CamelContextManager.mandatoryContext
   // Access to ProducerTemplate of that CamelContext
   val tpl = CamelContextManager.mandatoryTemplate

   // use ctx and tpl ...

   // Close application context (stop CamelService)
   appctx.close

**Java**

.. code-block:: java

   // TODO


If the CamelService doesn't reference a custom CamelContext then a
DefaultCamelContext is created (and accessible via the CamelContextManager).

.. code-block:: xml

   <beans xmlns="http://www.springframework.org/schema/beans"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:akka="http://www.akka.io/schema/akka"
          xsi:schemaLocation="
   http://www.springframework.org/schema/beans
   http://www.springframework.org/schema/beans/spring-beans-2.5.xsd
   http://www.akka.io/schema/akka
   http://akka.io/akka-0.10.xsd">

     <!-- Create a CamelService using DefaultCamelContext -->
     <akka:camel-service />

   </beans>


Kernel mode
-----------

For classes that are loaded by the Kernel or the Initializer, starting the
CamelService can be omitted, as discussed in the previous section. Since these
classes are loaded and instantiated before the CamelService is started (by
Akka), applications can make modifications to a CamelContext here as well (and
even provide their own CamelContext). Assuming there's a boot class
sample.camel.Boot configured in :ref:`configuration`.

.. code-block:: none

   akka {
     ...
     boot = ["sample.camel.Boot"]
     ...
   }

Modifications to the CamelContext can be done like in the following snippet.

**Scala**

.. code-block:: scala

   package sample.camel

   import org.apache.camel.builder.RouteBuilder

   import akka.camel.CamelContextManager

   class Boot {
     CamelContextManager.init

     // Customize CamelContext with application-specific routes
     CamelContextManager.mandatoryContext.addRoutes(new CustomRouteBuilder)

     // No need to start CamelService here. It will be started
     // when this classes has been loaded and instantiated.
   }

   class CustomRouteBuilder extends RouteBuilder {
     def configure {
       // ...
     }
   }

**Java**

.. code-block:: java

   // TODO


Custom Camel routes
===================

In all the examples so far, routes to consumer actors have been automatically
constructed by akka-camel, when the actor was started. Although the default
route construction templates, used by akka-camel internally, are sufficient for
most use cases, some applications may require more specialized routes to actors.
The akka-camel module provides two mechanisms for customizing routes to actors,
which will be explained in this section. These are

* Usage of :ref:`camel-components` to access (untyped) actor and actors.
  Any Camel route can use these components to access Akka actors.

* :ref:`camel-intercepting-route-construction` to (untyped) actor and actors.
  Default routes to consumer actors are extended using predefined extension
  points.


.. _camel-components:

Akka Camel components
---------------------

Akka actors can be access from Camel routes using the `actor`_ and
`typed-actor`_ Camel components, respectively. These components can be used to
access any Akka actor (not only consumer actors) from Camel routes, as described
in the following sections.

.. _actor: http://github.com/jboner/akka/blob/master/akka-camel/src/main/scala/akka/camel/component/ActorComponent.scala
.. _typed-actor: http://github.com/jboner/akka/blob/master/akka-camel/src/main/scala/akka/camel/component/TypedActorComponent.scala


Access to actors
----------------

To access (untyped) actors from custom Camel routes, the `actor`_ Camel
component should be used. It fully supports Camel's `asynchronous routing
engine`_.

.. _actor: http://github.com/jboner/akka/blob/master/akka-camel/src/main/scala/akka/camel/component/ActorComponent.scala
.. _asynchronous routing engine: http://camel.apache.org/asynchronous-routing-engine.html

This component accepts the following enpoint URI formats:

* ``actor:<actor-id>[?<options>]``
* ``actor:id:[<actor-id>][?<options>]``
* ``actor:uuid:[<actor-uuid>][?<options>]``

where ``<actor-id>`` and ``<actor-uuid>`` refer to ``actorRef.id`` and the
String-representation of ``actorRef.uuid``, respectively. The ``<options>`` are
name-value pairs separated by ``&`` (i.e. ``name1=value1&name2=value2&...``).


URI options
^^^^^^^^^^^

The following URI options are supported:

+----------+---------+---------+-------------------------------------------+
| Name     | Type    | Default | Description                               |
+==========+=========+=========+===========================================+
| blocking | Boolean | false   | If set to true, in-out message exchanges  |
|          |         |         | with the target actor will be made with   |
|          |         |         | the ``!!`` operator, otherwise with the   |
|          |         |         | ``!`` operator.                           |
|          |         |         |                                           |
|          |         |         | See also :ref:`camel-timeout`.            |
+----------+---------+---------+-------------------------------------------+
| autoack  | Boolean | true    | If set to true, in-only message exchanges |
|          |         |         | are auto-acknowledged when the message is |
|          |         |         | added to the actor's mailbox. If set to   |
|          |         |         | false, actors must acknowledge the        |
|          |         |         | receipt of the message.                   |
|          |         |         |                                           |
|          |         |         | See also :ref:`camel-acknowledgements`.   |
+----------+---------+---------+-------------------------------------------+

Here's an actor endpoint URI example containing an actor uuid::

   actor:uuid:12345678?blocking=true

In actor endpoint URIs that contain id: or uuid:, an actor identifier (id or
uuid) is optional. In this case, the in-message of an exchange produced to an
actor endpoint must contain a message header with name CamelActorIdentifier
(which is defined by the ActorComponent.ActorIdentifier field) and a value that
is the target actor's identifier. On the other hand, if the URI contains an
actor identifier, it can be seen as a default actor identifier that can be
overridden by messages containing a CamelActorIdentifier header.


Message headers
^^^^^^^^^^^^^^^

+----------------------+--------+-------------------------------------------+
| Name                 | Type   | Description                               |
+======================+========+===========================================+
| CamelActorIdentifier | String | Contains the identifier (id or uuid) of   |
|                      |        | the actor to route the message to. The    |
|                      |        | identifier is interpreted as actor id if  |
|                      |        | the URI contains id:, the identifier is   |
|                      |        | interpreted as uuid id the URI contains   |
|                      |        | uuid:. A uuid value may also be of type   |
|                      |        | Uuid (not only String). The header name   |
|                      |        | is defined by the                         |
|                      |        | ActorComponent.ActorIdentifier field.     |
+----------------------+--------+-------------------------------------------+

Here's another actor endpoint URI example that doesn't define an actor uuid. In
this case the target actor uuid must be defined by the CamelActorIdentifier
message header::

   actor:uuid:

In the following example, a custom route to an actor is created, using the
actor's uuid (i.e. actorRef.uuid). The route starts from a `Jetty`_ endpoint and
ends at the target actor.


**Scala**

.. code-block:: scala

   import org.apache.camel.builder.RouteBuilder

   import akka.actor._
   import akka.actor.Actor
   import akka.actor.Actor._
   import akka.camel.{Message, CamelContextManager, CamelServiceManager}

   object CustomRouteExample extends Application {
     val target = actorOf(Props[CustomRouteTarget])

     CamelServiceManager.startCamelService
     CamelContextManager.mandatoryContext.addRoutes(new CustomRouteBuilder(target.uuid))
   }

   class CustomRouteTarget extends Actor {
     def receive = {
       case msg: Message => self.reply("Hello %s" format msg.bodyAs[String])
     }
   }

   class CustomRouteBuilder(uuid: Uuid) extends RouteBuilder {
     def configure {
       val actorUri = "actor:uuid:%s" format uuid
       from("jetty:http://localhost:8877/camel/custom").to(actorUri)
     }
   }


**Java**

.. code-block:: java

   import com.eaio.uuid.UUID;

   import org.apache.camel.builder.RouteBuilder;
   import static akka.actor.Actors.*;
   import akka.actor.ActorRef;
   import akka.actor.UntypedActor;
   import akka.camel.CamelServiceManager;
   import akka.camel.CamelContextManager;
   import akka.camel.Message;

   public class CustomRouteExample {
       public static void main(String... args) throws Exception {
           ActorRef target = actorOf(new Props(CustomRouteTarget.class));
           CamelServiceManager.startCamelService();
           CamelContextManager.getMandatoryContext().addRoutes(new CustomRouteBuilder(target.getUuid()));
       }
   }

   public class CustomRouteTarget extends UntypedActor {
       public void onReceive(Object message) {
           Message msg = (Message) message;
           String body = msg.getBodyAs(String.class);
           getContext().tryReply(String.format("Hello %s", body));
       }
   }

   public class CustomRouteBuilder extends RouteBuilder {
       private UUID uuid;

       public CustomRouteBuilder(UUID uuid) {
           this.uuid = uuid;
       }

       public void configure() {
           String actorUri = String.format("actor:uuid:%s", uuid);
           from("jetty:http://localhost:8877/camel/custom").to(actorUri);
       }
   }

When the example is started, messages POSTed to
``http://localhost:8877/camel/custom`` are routed to the target actor.


Access to typed actors
----------------------

To access typed actor methods from custom Camel routes, the `typed-actor`_ Camel
component should be used. It is a specialization of the Camel `bean`_ component.
Applications should use the interface (endpoint URI syntax and options) as
described in the bean component documentation but with the typed-actor schema.
Typed Actors must be added to a `Camel registry`_ for being accessible by the
typed-actor component.

.. _typed-actor: http://github.com/jboner/akka/blob/master/akka-camel/src/main/scala/akka/camel/component/TypedActorComponent.scala
.. _bean: http://camel.apache.org/bean.html
.. _Camel registry: http://camel.apache.org/registry.html


.. _camel-typed-actors-using-spring:

Using Spring
^^^^^^^^^^^^

The following example shows how to access typed actors in a Spring application
context. For adding typed actors to the application context and for starting
:ref:`camel-spring-applications` the :ref:`spring-module` module is used in the
following example. It offers a ``<typed-actor>`` element to define typed actor
factory beans and a ``<camel-service>`` element to create and start a
CamelService.

.. code-block:: xml

   <!--
     context.xml
   -->
   <beans xmlns="http://www.springframework.org/schema/beans"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:akka="http://www.akka.io/schema/akka"
          xmlns:camel="http://camel.apache.org/schema/spring"
          xsi:schemaLocation="
   http://www.springframework.org/schema/beans
   http://www.springframework.org/schema/beans/spring-beans-2.5.xsd
   http://www.akka.io/schema/akka
   http://akka.io/akka-0.10.xsd
   http://camel.apache.org/schema/spring
   http://camel.apache.org/schema/spring/camel-spring.xsd">

     <bean id="routeBuilder" class="sample.SampleRouteBuilder" />

     <camel:camelContext id="camelContext">
       <camel:routeBuilder ref="routeBuilder" />
     </camel:camelContext>

     <akka:camel-service>
       <akka:camel-context ref="camelContext" />
     </akka:camel-service>

     <akka:typed-actor id="sample"
                       interface="sample.SampleTypedActor"
                       implementation="sample.SampleTypedActorImpl"
                       timeout="1000" />
   </beans>

SampleTypedActor is the typed actor interface and SampleTypedActorImpl in the
typed actor implementation class.

**Scala**

.. code-block:: scala

   package sample

   import akka.actor.TypedActor

   trait SampleTypedActor {
     def foo(s: String): String
   }

   class SampleTypedActorImpl extends TypedActor with SampleTypedActor {
     def foo(s: String) = "hello %s" format s
   }

**Java**

.. code-block:: java

   package sample;

   import akka.actor.TypedActor;

   public interface SampleTypedActor {
       public String foo(String s);
   }

   public class SampleTypedActorImpl extends TypedActor implements SampleTypedActor {

       public String foo(String s) {
           return "hello " + s;
       }
   }

The SampleRouteBuilder defines a custom route from the direct:test endpoint to
the sample typed actor using a typed-actor endpoint URI.

**Scala**

.. code-block:: scala

   package sample

   import org.apache.camel.builder.RouteBuilder

   class SampleRouteBuilder extends RouteBuilder {
     def configure = {
       // route to typed actor
       from("direct:test").to("typed-actor:sample?method=foo")
     }
   }

**Java**

.. code-block:: java

   package sample;

   import org.apache.camel.builder.RouteBuilder;

   public class SampleRouteBuilder extends RouteBuilder {
       public void configure() {
           // route to typed actor
           from("direct:test").to("typed-actor:sample?method=foo");
       }
   }

The typed-actor endpoint URI syntax is:::

   typed-actor:<bean-id>?method=<method-name>

where ``<bean-id>`` is the id of the bean in the Spring application context and
``<method-name>`` is the name of the typed actor method to invoke.

Usage of the custom route for sending a message to the typed actor is shown in
the following snippet.

**Scala**

.. code-block:: scala

   package sample

   import org.springframework.context.support.ClassPathXmlApplicationContext
   import akka.camel.CamelContextManager

   // load Spring application context (starts CamelService)
   val appctx = new ClassPathXmlApplicationContext("/context-standalone.xml")

   // access 'sample' typed actor via custom route
   assert("hello akka" == CamelContextManager.mandatoryTemplate.requestBody("direct:test", "akka"))

   // close Spring application context (stops CamelService)
   appctx.close

**Java**

.. code-block:: java

   package sample;

   import org.springframework.context.support.ClassPathXmlApplicationContext;
   import akka.camel.CamelContextManager;

   // load Spring application context
   ClassPathXmlApplicationContext appctx = new ClassPathXmlApplicationContext("/context-standalone.xml");

   // access 'externally' registered typed actors with typed-actor component
   assert("hello akka" == CamelContextManager.getMandatoryTemplate().requestBody("direct:test", "akka"));

   // close Spring application context (stops CamelService)
   appctx.close();

The application uses a Camel `producer template`_ to access the typed actor via
the ``direct:test`` endpoint.

.. _producer template: http://camel.apache.org/producertemplate.html


Without Spring
^^^^^^^^^^^^^^

Usage of :ref:`spring-module` for adding typed actors to the Camel registry and
starting a CamelService is optional. Setting up a Spring-less application for
accessing typed actors is shown in the next example.

**Scala**

.. code-block:: scala

   package sample

   import org.apache.camel.impl.{DefaultCamelContext, SimpleRegistry}
   import akka.actor.TypedActor
   import akka.camel.CamelContextManager
   import akka.camel.CamelServiceManager._

   // register typed actor
   val registry = new SimpleRegistry
   registry.put("sample", TypedActor.newInstance(classOf[SampleTypedActor], classOf[SampleTypedActorImpl]))

   // customize CamelContext
   CamelContextManager.init(new DefaultCamelContext(registry))
   CamelContextManager.mandatoryContext.addRoutes(new SampleRouteBuilder)

   startCamelService

   // access 'sample' typed actor via custom route
   assert("hello akka" == CamelContextManager.mandatoryTemplate.requestBody("direct:test", "akka"))

   stopCamelService

**Java**

.. code-block:: java

   package sample;

   // register typed actor
   SimpleRegistry registry = new SimpleRegistry();
   registry.put("sample", TypedActor.newInstance(SampleTypedActor.class, SampleTypedActorImpl.class));

   // customize CamelContext
   CamelContextManager.init(new DefaultCamelContext(registry));
   CamelContextManager.getMandatoryContext().addRoutes(new SampleRouteBuilder());

   startCamelService();

   // access 'sample' typed actor via custom route
   assert("hello akka" == CamelContextManager.getMandatoryTemplate().requestBody("direct:test", "akka"));

   stopCamelService();

Here, `SimpleRegistry`_, a java.util.Map based registry, is used to register
typed actors. The CamelService is started and stopped programmatically.

.. _SimpleRegistry: https://svn.apache.org/repos/asf/camel/trunk/camel-core/src/main/java/org/apache/camel/impl/SimpleRegistry.java


.. _camel-intercepting-route-construction:

Intercepting route construction
-------------------------------

The previous section, :ref:`camel-components`, explained how to setup a route to
an (untyped) actor or typed actor manually. It was the application's
responsibility to define the route and add it to the current CamelContext. This
section explains a more conventient way to define custom routes: akka-camel is
still setting up the routes to consumer actors (and adds these routes to the
current CamelContext) but applications can define extensions to these routes.
Extensions can be defined with Camel's `Java DSL`_ or `Scala DSL`_. For example,
an extension could be a custom error handler that redelivers messages from an
endpoint to an actor's bounded mailbox when the mailbox was full.

.. _Java DSL: http://camel.apache.org/dsl.html
.. _Scala DSL: http://camel.apache.org/scala-dsl.html

The following examples demonstrate how to extend a route to a consumer actor for
handling exceptions thrown by that actor. To simplify the example, we configure
:ref:`camel-blocking-exchanges` which reports any exception, that is thrown by
receive, directly back to the Camel route. One could also report exceptions
asynchronously using a Failure reply (see also `this article`__) but we'll do it
differently here.

__ http://krasserm.blogspot.com/2011/02/akka-consumer-actors-new-features-and.html


Actors (untyped)
^^^^^^^^^^^^^^^^

**Scala**

.. code-block:: scala

   import akka.actor.Actor
   import akka.camel.Consumer

   import org.apache.camel.builder.Builder
   import org.apache.camel.model.RouteDefinition

   class ErrorHandlingConsumer extends Actor with Consumer {
     def endpointUri = "direct:error-handler-test"

     // Needed to propagate exception back to caller
     override def blocking = true

     onRouteDefinition {rd: RouteDefinition =>
       // Catch any exception and handle it by returning the exception message as response
       rd.onException(classOf[Exception]).handled(true).transform(Builder.exceptionMessage).end
     }

     protected def receive = {
       case msg: Message => throw new Exception("error: %s" format msg.body)
     }
   }

**Java**

.. code-block:: java

   import akka.camel.UntypedConsumerActor;

   import org.apache.camel.builder.Builder;
   import org.apache.camel.model.ProcessorDefinition;
   import org.apache.camel.model.RouteDefinition;

   public class SampleErrorHandlingConsumer extends UntypedConsumerActor {

       public String getEndpointUri() {
           return "direct:error-handler-test";
       }

       // Needed to propagate exception back to caller
       public boolean isBlocking() {
           return true;
       }

       public void preStart() {
           onRouteDefinition(new RouteDefinitionHandler() {
               public ProcessorDefinition<?> onRouteDefinition(RouteDefinition rd) {
                   // Catch any exception and handle it by returning the exception message as response
                   return rd.onException(Exception.class).handled(true).transform(Builder.exceptionMessage()).end();
               }
           });
       }

       public void onReceive(Object message) throws Exception {
           Message msg = (Message)message;
           String body = msg.getBodyAs(String.class);
           throw new Exception(String.format("error: %s", body));
      }

   }



For (untyped) actors, consumer route extensions are defined by calling the
onRouteDefinition method with a route definition handler. In Scala, this is a
function of type ``RouteDefinition => ProcessorDefinition[_]``, in Java it is an
instance of ``RouteDefinitionHandler`` which is defined as follows.

.. code-block:: scala

   package akka.camel

   import org.apache.camel.model.RouteDefinition
   import org.apache.camel.model.ProcessorDefinition

   trait RouteDefinitionHandler {
     def onRouteDefinition(rd: RouteDefinition): ProcessorDefinition[_]
   }

The akka-camel module creates a RouteDefinition instance by calling
from(endpointUri) on a Camel RouteBuilder (where endpointUri is the endpoint URI
of the consumer actor) and passes that instance as argument to the route
definition handler \*). The route definition handler then extends the route and
returns a ProcessorDefinition (in the above example, the ProcessorDefinition
returned by the end method. See the `org.apache.camel.model`__ package for
details). After executing the route definition handler, akka-camel finally calls
a to(actor:uuid:actorUuid) on the returned ProcessorDefinition to complete the
route to the comsumer actor (where actorUuid is the uuid of the consumer actor).

\*) Before passing the RouteDefinition instance to the route definition handler,
akka-camel may make some further modifications to it.

__ https://svn.apache.org/repos/asf/camel/trunk/camel-core/src/main/java/org/apache/camel/model/


Typed actors
^^^^^^^^^^^^

For typed consumer actors to define a route definition handler, they must
provide a RouteDefinitionHandler implementation class with the @consume
annotation. The implementation class must have a no-arg constructor. Here's an
example (in Java).

.. code-block:: java

   import org.apache.camel.builder.Builder;
   import org.apache.camel.model.ProcessorDefinition;
   import org.apache.camel.model.RouteDefinition;

   public class SampleRouteDefinitionHandler implements RouteDefinitionHandler {
       public ProcessorDefinition<?> onRouteDefinition(RouteDefinition rd) {
           return rd.onException(Exception.class).handled(true).transform(Builder.exceptionMessage()).end();
       }
   }

It can be used as follows.

**Scala**

.. code-block:: scala

   trait TestTypedConsumer {
     @consume(value="direct:error-handler-test", routeDefinitionHandler=classOf[SampleRouteDefinitionHandler])
     def foo(s: String): String
   }

   // implementation class omitted

**Java**

.. code-block:: java

   public interface SampleErrorHandlingTypedConsumer {

       @consume(value="direct:error-handler-test", routeDefinitionHandler=SampleRouteDefinitionHandler.class)
       String foo(String s);

   }

   // implementation class omitted


.. _camel-examples:

Examples
========

For all features described so far, there's running sample code in
`akka-sample-camel`_. The examples in `sample.camel.Boot`_ are started during
Kernel startup because this class has been added to the boot :ref:`configuration`.

.. _akka-sample-camel: http://github.com/jboner/akka/tree/master/akka-samples/akka-sample-camel/
.. _sample.camel.Boot: http://github.com/jboner/akka/blob/master/akka-samples/akka-sample-camel/src/main/scala/sample/camel/Boot.scala

.. code-block:: none

   akka {
     ...
     boot = ["sample.camel.Boot", ...]
     ...
   }

If you don't want to have these examples started during Kernel startup, delete
it from the :ref:`configuration`. Other examples are standalone applications (i.e. classes with a
main method) that can be started from `sbt`_.

.. _sbt: http://code.google.com/p/simple-build-tool/

.. code-block:: none

   $ sbt
   [info] Building project akka 2.0-SNAPSHOT against Scala 2.9.0
   [info]    using AkkaModulesParentProject with sbt 0.7.7 and Scala 2.7.7
   > project akka-sample-camel
   Set current project to akka-sample-camel 2.0-SNAPSHOT
   > run
   ...
   Multiple main classes detected, select one to run:

    [1] sample.camel.ClientApplication
    [2] sample.camel.ServerApplication
    [3] sample.camel.StandaloneSpringApplication
    [4] sample.camel.StandaloneApplication
    [5] sample.camel.StandaloneFileApplication
    [6] sample.camel.StandaloneJmsApplication


Some of the examples in `akka-sample-camel`_ are described in more detail in the
following subsections.


.. _camel-async-example:

Asynchronous routing and transformation example
-----------------------------------------------

This example demonstrates how to implement consumer and producer actors that
support :ref:`camel-asynchronous-routing` with their Camel endpoints. The sample
application transforms the content of the Akka homepage, http://akka.io, by
replacing every occurrence of *Akka* with *AKKA*. After starting
the :ref:`microkernel`, direct the browser to http://localhost:8875 and the
transformed Akka homepage should be displayed. Please note that this example
will probably not work if you're behind an HTTP proxy.

The following figure gives an overview how the example actors interact with
external systems and with each other. A browser sends a GET request to
http://localhost:8875 which is the published endpoint of the ``HttpConsumer``
actor. The ``HttpConsumer`` actor forwards the requests to the ``HttpProducer``
actor which retrieves the Akka homepage from http://akka.io. The retrieved HTML
is then forwarded to the ``HttpTransformer`` actor which replaces all occurences
of *Akka* with *AKKA*. The transformation result is sent back the HttpConsumer
which finally returns it to the browser.

.. image:: camel-async-interact.png

Implementing the example actor classes and wiring them together is rather easy
as shown in the following snippet (see also `sample.camel.Boot`_).

.. code-block:: scala

   import org.apache.camel.Exchange
   import akka.actor.Actor._
   import akka.actor.{Actor, ActorRef}
   import akka.camel.{Producer, Message, Consumer}

   class HttpConsumer(producer: ActorRef) extends Actor with Consumer {
     def endpointUri = "jetty:http://0.0.0.0:8875/"

     protected def receive = {
       case msg => producer forward msg
     }
   }

   class HttpProducer(transformer: ActorRef) extends Actor with Producer {
     def endpointUri = "jetty://http://akka.io/?bridgeEndpoint=true"

     override protected def receiveBeforeProduce = {
       // only keep Exchange.HTTP_PATH message header (which needed by bridge endpoint)
       case msg: Message => msg.setHeaders(msg.headers(Set(Exchange.HTTP_PATH)))
     }

     override protected def receiveAfterProduce = {
       // do not reply but forward result to transformer
       case msg => transformer forward msg
     }
   }

   class HttpTransformer extends Actor {
     protected def receive = {
       case msg: Message => self.reply(msg.transformBody {body: String => body replaceAll ("Akka ", "AKKA ")})
       case msg: Failure => self.reply(msg)
     }
   }

   // Wire and start the example actors
   val httpTransformer = actorOf(Props(new HttpTransformer))
   val httpProducer = actorOf(Props(new HttpProducer(httpTransformer)))
   val httpConsumer = actorOf(Props(new HttpConsumer(httpProducer)))

The `jetty endpoints`_ of HttpConsumer and HttpProducer support asynchronous
in-out message exchanges and do not allocate threads for the full duration of
the exchange. This is achieved by using `Jetty continuations`_ on the
consumer-side and by using `Jetty's asynchronous HTTP client`_ on the producer
side. The following high-level sequence diagram illustrates that.

.. _jetty endpoints: http://camel.apache.org/jetty.html
.. _Jetty continuations: http://wiki.eclipse.org/Jetty/Feature/Continuations
.. _Jetty's asynchronous HTTP client: http://wiki.eclipse.org/Jetty/Tutorial/HttpClient

.. image:: camel-async-sequence.png


Custom Camel route example
--------------------------

This section also demonstrates the combined usage of a ``Producer`` and a
``Consumer`` actor as well as the inclusion of a custom Camel route. The
following figure gives an overview.

.. image:: camel-custom-route.png

* A consumer actor receives a message from an HTTP client

* It forwards the message to another actor that transforms the message (encloses
  the original message into hyphens)

* The transformer actor forwards the transformed message to a producer actor

* The producer actor sends the message to a custom Camel route beginning at the
  ``direct:welcome`` endpoint

* A processor (transformer) in the custom Camel route prepends "Welcome" to the
  original message and creates a result message

* The producer actor sends the result back to the consumer actor which returns
  it to the HTTP client


The example is part of `sample.camel.Boot`_. The consumer, transformer and
producer actor implementations are as follows.

.. code-block:: scala

   package sample.camel

   import akka.actor.{Actor, ActorRef}
   import akka.camel.{Message, Consumer}

   class Consumer3(transformer: ActorRef) extends Actor with Consumer {
     def endpointUri = "jetty:http://0.0.0.0:8877/camel/welcome"

     def receive = {
       // Forward a string representation of the message body to transformer
       case msg: Message => transformer.forward(msg.setBodyAs[String])
     }
   }

   class Transformer(producer: ActorRef) extends Actor {
     protected def receive = {
       // example: transform message body "foo" to "- foo -" and forward result to producer
       case msg: Message => producer.forward(msg.transformBody((body: String) => "- %s -" format body))
     }
   }

   class Producer1 extends Actor with Producer {
     def endpointUri = "direct:welcome"
   }

The producer actor knows where to reply the message to because the consumer and
transformer actors have forwarded the original sender reference as well. The
application configuration and the route starting from direct:welcome are as
follows.

.. code-block:: scala

   package sample.camel

   import org.apache.camel.builder.RouteBuilder
   import org.apache.camel.{Exchange, Processor}

   import akka.actor.Actor._
   import akka.camel.CamelContextManager

   class Boot {
     CamelContextManager.init()
     CamelContextManager.mandatoryContext.addRoutes(new CustomRouteBuilder)

     val producer = actorOf(Props[Producer1])
     val mediator = actorOf(Props(new Transformer(producer)))
     val consumer = actorOf(Props(new Consumer3(mediator)))
   }

   class CustomRouteBuilder extends RouteBuilder {
     def configure {
       from("direct:welcome").process(new Processor() {
         def process(exchange: Exchange) {
           // Create a 'welcome' message from the input message
           exchange.getOut.setBody("Welcome %s" format exchange.getIn.getBody)
         }
       })
     }
   }

To run the example, start the :ref:`microkernel` and POST a message to
``http://localhost:8877/camel/welcome``.

.. code-block:: none

   curl -H "Content-Type: text/plain" -d "Anke" http://localhost:8877/camel/welcome

The response should be:

.. code-block:: none

   Welcome - Anke -


Publish-subcribe example
------------------------

JMS
^^^

This section demonstrates how akka-camel can be used to implement
publish/subscribe for actors. The following figure sketches an example for
JMS-based publish/subscribe.

.. image:: camel-pubsub.png

A consumer actor receives a message from an HTTP client. It sends the message to
a JMS producer actor (publisher). The JMS producer actor publishes the message
to a JMS topic. Two other actors that subscribed to that topic both receive the
message. The actor classes used in this example are shown in the following
snippet.

.. code-block:: scala

   package sample.camel

   import akka.actor.{Actor, ActorRef}
   import akka.camel.{Producer, Message, Consumer}

   class Subscriber(name:String, uri: String) extends Actor with Consumer {
     def endpointUri = uri

     protected def receive = {
       case msg: Message => println("%s received: %s" format (name, msg.body))
     }
   }

   class Publisher(name: String, uri: String) extends Actor with Producer {
     self.id = name

     def endpointUri = uri

     // one-way communication with JMS
     override def oneway = true
   }

   class PublisherBridge(uri: String, publisher: ActorRef) extends Actor with Consumer {
     def endpointUri = uri

     protected def receive = {
       case msg: Message => {
         publisher ! msg.bodyAs[String]
         self.reply("message published")
       }
     }
   }

Wiring these actors to implement the above example is as simple as

.. code-block:: scala

   package sample.camel

   import org.apache.camel.impl.DefaultCamelContext
   import org.apache.camel.spring.spi.ApplicationContextRegistry
   import org.springframework.context.support.ClassPathXmlApplicationContext

   import akka.actor.Actor._
   import akka.camel.CamelContextManager

   class Boot {
     // Create CamelContext with Spring-based registry and custom route builder
     val context = new ClassPathXmlApplicationContext("/context-jms.xml", getClass)
     val registry = new ApplicationContextRegistry(context)
     CamelContextManager.init(new DefaultCamelContext(registry))

     // Setup publish/subscribe example
     val jmsUri = "jms:topic:test"
     val jmsSubscriber1 = actorOf(Props(new Subscriber("jms-subscriber-1", jmsUri)))
     val jmsSubscriber2 = actorOf(Props(new Subscriber("jms-subscriber-2", jmsUri)))
     val jmsPublisher   = actorOf(Props(new Publisher("jms-publisher", jmsUri)))

     val jmsPublisherBridge = actorOf(Props(new PublisherBridge("jetty:http://0.0.0.0:8877/camel/pub/jms", jmsPublisher)))
   }

To publish messages to subscribers one could of course also use the JMS API
directly; there's no need to do that over a JMS producer actor as in this
example. For the example to work, Camel's `jms`_ component needs to be
configured with a JMS connection factory which is done in a Spring application
context XML file (context-jms.xml).

.. _jms: http://camel.apache.org/jms.html

.. code-block:: xml

   <beans xmlns="http://www.springframework.org/schema/beans"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="
   http://www.springframework.org/schema/beans
   http://www.springframework.org/schema/beans/spring-beans-2.5.xsd">

     <!-- ================================================================== -->
     <!--  Camel JMS component and ActiveMQ setup                            -->
     <!-- ================================================================== -->

     <bean id="jms" class="org.apache.camel.component.jms.JmsComponent">
         <property name="configuration" ref="jmsConfig"/>
     </bean>

     <bean id="jmsConfig" class="org.apache.camel.component.jms.JmsConfiguration">
         <property name="connectionFactory" ref="singleConnectionFactory"/>
     </bean>

     <bean id="singleConnectionFactory" class="org.springframework.jms.connection.SingleConnectionFactory">
         <property name="targetConnectionFactory" ref="jmsConnectionFactory"/>
     </bean>

     <bean id="jmsConnectionFactory" class="org.apache.activemq.ActiveMQConnectionFactory">
         <property name="brokerURL" value="vm://testbroker"/>
     </bean>

   </beans>

To run the example, start the :ref:`microkernel` and POST a
message to ``http://localhost:8877/camel/pub/jms``.

.. code-block:: none

   curl -H "Content-Type: text/plain" -d "Happy hAkking" http://localhost:8877/camel/pub/jms

The HTTP response body should be

.. code-block:: none

   message published

On the console, where you started the Akka Kernel, you should see something like

.. code-block:: none

   ...
   INF [20100622-11:49:57.688] camel: jms-subscriber-2 received: Happy hAkking
   INF [20100622-11:49:57.688] camel: jms-subscriber-1 received: Happy hAkking


Cometd
^^^^^^

Publish/subscribe with `CometD`_ is equally easy using `Camel's cometd
component`_.

.. _CometD: http://cometd.org/
.. _Camel's cometd component: http://camel.apache.org/cometd.html

.. image:: camel-pubsub2.png

All actor classes from the JMS example can re-used, only the endpoint URIs need
to be changed.

.. code-block:: scala

   package sample.camel

   import org.apache.camel.impl.DefaultCamelContext
   import org.apache.camel.spring.spi.ApplicationContextRegistry
   import org.springframework.context.support.ClassPathXmlApplicationContext

   import akka.actor.Actor._
   import akka.camel.CamelContextManager

   class Boot {
     // ...

     // Setup publish/subscribe example
     val cometdUri = "cometd://localhost:8111/test/abc?resourceBase=target"
     val cometdSubscriber = actorOf(Props(new Subscriber("cometd-subscriber", cometdUri)))
     val cometdPublisher  = actorOf(Props(new Publisher("cometd-publisher", cometdUri)))

     val cometdPublisherBridge = actorOf(Props(new PublisherBridge("jetty:http://0.0.0.0:8877/camel/pub/cometd", cometdPublisher)))
   }


Quartz Scheduler Example
------------------------

Here is an example showing how simple is to implement a cron-style scheduler by
using the Camel Quartz component in Akka.

The following example creates a "timer" actor which fires a message every 2
seconds:

.. code-block:: scala

   package com.dimingo.akka

   import akka.actor.Actor
   import akka.actor.Actor.actorOf

   import akka.camel.{Consumer, Message}
   import akka.camel.CamelServiceManager._

   class MyQuartzActor extends Actor with Consumer {

       def endpointUri = "quartz://example?cron=0/2+*+*+*+*+?"

       def receive = {

           case msg => println("==============> received %s " format msg)

       } // end receive

   } // end MyQuartzActor

   object MyQuartzActor {

       def main(str: Array[String]) {

           // start the Camel service
           startCamelService

           // create and start a quartz actor
           val myActor = actorOf(Props[MyQuartzActor])

       } // end main

   } // end MyQuartzActor

The full working example is available for download here:
http://www.dimingo.com/akka/examples/example-akka-quartz.tar.gz

You can launch it using the maven command:

.. code-block:: none

  $ mvn scala:run -DmainClass=com.dimingo.akka.MyQuartzActor

For more information about the Camel Quartz component, see here:
http://camel.apache.org/quartz.html
