Akka
====

**Simpler Scalability, Fault-Tolerance, Concurrency & Remoting through Actors**

----
We believe that writing correct concurrent, fault-tolerant and scalable applications is too hard. Most of the time it's because we are using the wrong tools and the wrong level of abstraction. Akka is here to change that. Using the Actor Model together with Software Transactional Memory we raise the abstraction level and provide a better platform to build correct concurrent and scalable applications. For fault-tolerance we adopt the "Let it crash" / "Embrace failure" model which have been used with great success in the telecom industry to build applications that self-heals, systems that never stop. Actors also provides the abstraction for transparent distribution and the basis for truly scalable and fault-tolerant applications. Akka is Open Source and available under the Apache 2 License.
----

Akka is split up into two different parts:
* Akka - Reflects all the sections under 'Scala API' and 'Java API' in the navigation bar.
* Akka Modules - Reflects all the sections under 'Add-on modules' in the navigation bar.

Download from `<http://akka.io/downloads/>`_

News: Akka 1.0 final is released
================================

1.0 documentation:
==================

This documentation covers the latest release ready code in 'master' branch in the repository.
If you want the documentation for the 1.0 release you can find it `here <http://akka.io/docs/akka-1.0/space.menu.html>`_.

You can watch the recording of the `Akka talk at JFokus in Feb 2011 <http://79.136.112.58/ability/show/xaimkwdli/a2_20110216_1110/mainshow.asp?STREAMID=1>`_.

`<media type="custom" key="8924178">`_

**Akka implements a unique hybrid of:**
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* `Actors <untyped-actors-java>`_, which gives you:
** Simple and high-level abstractions for concurrency and parallelism.
** Asynchronous, non-blocking and highly performant event-driven programming model.
** Very lightweight event-driven processes (create ~6.5 million actors on 4 G RAM).
* `Failure management <fault-tolerance-java>`_ through supervisor hierarchies with `let-it-crash <http://letitcrash.com>`_ semantics. Excellent for writing highly fault-tolerant systems that never stop, systems that self-heal.
* `Software Transactional Memory <stm-java>`_ (STM). (Distributed transactions coming soon).
* `Transactors <transactors-java>`_: combine actors and STM into transactional actors. Allows you to compose atomic message flows with automatic retry and rollback.
* `Remote actors <remote-actors-java>`_: highly performant distributed actors with remote supervision and error management.
* Java and Scala API.

**Akka also has a set of add-on modules:**
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* `Camel <camel>`_: Expose actors as Apache Camel endpoints.
* `Spring <spring-integration>`_: Wire up typed actors in the Spring config using Akka's namespace.
* `REST <rest>`_ (JAX-RS): Expose actors as REST services.
* `OSGi <osgi>`_: Akka and all its dependency is OSGi enabled.
* `Mist <http#Mist%20-%20Lightweight%20Asynchronous%20HTTP>`_: Expose actors as asynchronous HTTP services.
* `Security <security>`_: Basic, Digest and Kerberos based security.
* `Microkernel <microkernel>`_: Run Akka as a stand-alone self-hosted kernel.
* `FSM <fsm-scala>`_: Finite State Machine support.
* `JTA <jta>`_: Let the STM interoperate with other transactional resources.
* `Pub/Sub <pubsub>`_: Publish-Subscribe across remote nodes.

**Akka can be used in two different ways:**
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* As a library: used by a web app, to be put into ‘WEB-INF/lib’ or as a regular JAR on your classpath.
* As a microkernel: stand-alone kernel, embedding a servlet container and all the other modules.

See the `Use-case and Deployment Scenarios <deployment-scenarios>`_ for details.
