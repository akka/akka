
.. _what-is-akka:

###############
 What is Akka?
###############

**Scalable real-time transaction processing**

We believe that writing correct concurrent, fault-tolerant and scalable
applications is too hard. Most of the time it's because we are using the wrong
tools and the wrong level of abstraction. Akka is here to change that. Using the
Actor Model we raise the abstraction level and provide a better platform to build
correct concurrent and scalable applications. For fault-tolerance we adopt the
"Let it crash" model which have been used with great success in the telecom industry to build
applications that self-heals, systems that never stop. Actors also provides the
abstraction for transparent distribution and the basis for truly scalable and
fault-tolerant applications.

Akka is Open Source and available under the Apache 2 License.

Download from http://akka.io/downloads/


Akka implements a unique hybrid
===============================

Actors
------

Actors give you:

  - Simple and high-level abstractions for concurrency and parallelism.
  - Asynchronous, non-blocking and highly performant event-driven programming model.
  - Very lightweight event-driven processes (approximately 2.7 million actors per GB RAM).

See :ref:`actors-scala` and :ref:`untyped-actors-java`

Fault Tolerance
---------------

Fault tolerance through supervisor hierarchies with "let-it-crash"
semantics. Excellent for writing highly fault-tolerant systems that never stop,
systems that self-heal. Supervisor hierarchies can span over multiple JVMs to
provide truly fault-tolerant systems.

See :ref:`fault-tolerance-scala` and :ref:`fault-tolerance-java`

Location Transparency
---------------------
Everything in Akka is designed to work in a distributed environment: all
interactions of actors use purely message passing and everything is asynchronous.

For an overview of the remoting see :ref:`remoting`

Transactors
-----------

Transactors combine actors and STM (Software Transactional Memory) into transactional actors.
It allows you to compose atomic message flows with automatic retry and rollback.

See :ref:`transactors-scala` and :ref:`transactors-java`


Scala and Java APIs
===================

Akka has both a :ref:`scala-api` and a :ref:`java-api`.


Akka can be used in two different ways
======================================

- As a library: used by a web app, to be put into ``WEB-INF/lib`` or as a regular
  JAR on your classpath.

- As a microkernel: stand-alone kernel to drop your application into.

See the :ref:`deployment-scenarios` for details.

What happened to Cloudy Akka?
=============================

The commercial offering was earlier referred to as Cloudy Akka. This offering 
consisted of two things:

- Cluster support for Akka
- Monitoring & Management (formerly called Atmos)

Cloudy Akka have been discontinued and the Cluster support is now being moved into the 
Open Source version of Akka (the upcoming Akka 2.1), while the Monitoring & Management 
(Atmos) is now rebranded into Typesafe Console and is part of the commercial subscription 
for the Typesafe Stack (see below for details).

Typesafe Stack
==============

Akka is now also part of the `Typesafe Stack <http://typesafe.com/stack>`_.

The Typesafe Stack is a modern software platform that makes it easy for developers
to build scalable software applications. It combines the Scala programming language,
Akka, the Play! web framework and robust developer tools in a simple package that
integrates seamlessly with existing Java infrastructure.

The Typesafe Stack is all fully open source.

Typesafe Console
================

On top of the Typesafe Stack we also have commercial product called Typesafe
Console which provides the following features:

#. Slick Web UI with real-time view into the system
#. Management through Dashboard, JMX and REST
#. Dapper-style tracing of messages across components and remote nodes
#. Real-time statistics
#. Very low overhead monitoring agents (should always be on in production)
#. Consolidation of statistics and logging information to a single node
#. Storage of statistics data for later processing
#. Provisioning and rolling upgrades

Read more `here <http://typesafe.com/products/typesafe-subscription>`_.
