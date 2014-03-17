.. _what-is-akka:

###############
 What is Akka?
###############

**Scalable real-time transaction processing**

We believe that writing correct concurrent, fault-tolerant and scalable
applications is too hard. Most of the time it's because we are using the wrong
tools and the wrong level of abstraction. Akka is here to change that. Using
the Actor Model we raise the abstraction level and provide a better platform to
build scalable, resilient and responsive applications—see the `Reactive
Manifesto <http://reactivemanifesto.org/>`_ for more details. For
fault-tolerance we adopt the "let it crash" model which the telecom industry
has used with great success to build applications that self-heal and systems
that never stop. Actors also provide the abstraction for transparent
distribution and the basis for truly scalable and fault-tolerant applications.

Akka is Open Source and available under the Apache 2 License.

Download from http://akka.io/downloads.

Please note that all code samples compile, so if you want direct access to the sources, have a look
over at the Akka Docs subproject on github: for `Java <@github@/akka-docs/rst/java/code/docs>`_ 
and `Scala <@github@/akka-docs/rst/scala/code/docs>`_.


Akka implements a unique hybrid
===============================

Actors
------

Actors give you:

- Simple and high-level abstractions for concurrency and parallelism.
- Asynchronous, non-blocking and highly performant event-driven programming model.
- Very lightweight event-driven processes (several million actors per GB of heap memory).

See the chapter for :ref:`Scala <actors-scala>` or :ref:`Java <untyped-actors-java>`.

Fault Tolerance
---------------

- Supervisor hierarchies with "let-it-crash" semantics.
- Supervisor hierarchies can span over multiple JVMs to provide truly fault-tolerant systems.
- Excellent for writing highly fault-tolerant systems that self-heal and never stop.

See :ref:`Fault Tolerance (Scala) <fault-tolerance-scala>` and :ref:`Fault Tolerance (Java) <fault-tolerance-java>`.

Location Transparency
---------------------
Everything in Akka is designed to work in a distributed environment: all
interactions of actors use pure message passing and everything is asynchronous.

For an overview of the cluster support see the :ref:`Java <cluster_usage_java>`
and :ref:`Scala <cluster_usage_scala>` documentation chapters.

Persistence
-----------

Messages received by an actor can optionally be persisted and replayed when the actor is started or
restarted. This allows actors to recover their state, even after JVM crashes or when being migrated
to another node.

You can find more details in the respective chapter for :ref:`Java <persistence-java>` or :ref:`Scala <persistence-scala>`.

Scala and Java APIs
===================

Akka has both a :ref:`scala-api` and a :ref:`java-api`.


Akka can be used in two different ways
======================================

- As a library: used by a web app, to be put into ``WEB-INF/lib`` or as a regular
  JAR on your classpath.

- As a microkernel: stand-alone kernel to drop your application into.

See the :ref:`deployment-scenarios` for details.

Commercial Support
==================

Akka is available from Typesafe Inc. under a commercial license which includes
development or production support, read more `here
<http://www.typesafe.com/how/subscription>`_.

