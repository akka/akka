
.. _what-is-akka:

###############
 What is Akka?
###############


**Simpler Scalability, Fault-Tolerance, Concurrency & Remoting through Actors**

We believe that writing correct concurrent, fault-tolerant and scalable
applications is too hard. Most of the time it's because we are using the wrong
tools and the wrong level of abstraction. Akka is here to change that. Using the
Actor Model together with Software Transactional Memory we raise the abstraction
level and provide a better platform to build correct concurrent and scalable
applications. For fault-tolerance we adopt the Let it crash/Embrace failure
model which have been used with great success in the telecom industry to build
applications that self-heals, systems that never stop. Actors also provides the
abstraction for transparent distribution and the basis for truly scalable and
fault-tolerant applications. Akka is Open Source and available under the Apache
2 License.


Download from http://akka.io/downloads/


Akka implements a unique hybrid
===============================

- :ref:`untyped-actors-java`, which gives you:

  - Simple and high-level abstractions for concurrency and parallelism.
  - Asynchronous, non-blocking and highly performant event-driven programming model.
  - Very lightweight event-driven processes (create ~6.5 million actors on 4GB RAM).

- :ref:`fault-tolerance-java` through supervisor hierarchies with "let-it-crash"
  semantics. Excellent for writing highly fault-tolerant systems that never
  stop, systems that self-heal.

- :ref:`stm-java` (STM). (Distributed transactions coming soon).

- :ref:`transactors-java`: combine actors and STM into transactional
  actors. Allows you to compose atomic message flows with automatic retry and
  rollback.

- :ref:`remote-actors-java`: highly performant distributed actors with remote
  supervision and error management.

- :ref:`java-api` and :ref:`scala-api`


Akka can be used in two different ways
======================================

- As a library: used by a web app, to be put into ‘WEB-INF/lib’ or as a regular
  JAR on your classpath.

- As a microkernel: stand-alone kernel, embedding a servlet container and all
  the other modules.


See the :ref:`deployment-scenarios` for details.
