

Use-case and Deployment Scenarios
=================================

=

How and in which use-case and deployment scenarios can I use Akka?
==================================================================

Akka can be used in two different ways:
* As a library: used as a regular JAR on the classpath and/or in a web app, to be put into ‘WEB-INF/lib’
* As a microkernel: stand-alone microkernel, embedding a servlet container along with many other services.

Using Akka as library
---------------------

This is most likely what you want if you are building Web applications.
There are several ways you can use Akka in Library mode by adding more and more modules to the stack.

Actors as services
^^^^^^^^^^^^^^^^^^

The simplest way you can use Akka is to use the actors as services in your Web application. All that’s needed to do that is to put the Akka charts as well as its dependency jars into ‘WEB-INF/lib’. You also need to put the ‘akka.conf’ config file in the ‘$AKKA_HOME/config’ directory.
Now you can create your Actors as regular services referenced from your Web application. You should also be able to use the Remoting service, e.g. be able to make certain Actors remote on other hosts. Please note that remoting service does not speak HTTP over port 80, but a custom protocol over the port is specified in ‘akka.conf’.
`<image:http://a.imagehost.org/0116/akka-as-library-1.png>`_

^

Actors as services with Software Transactional Memory (STM)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

As in the above, but with the addition of using the STM module to allow transactional memory across many Actors (no persistence, just in-memory).
`<image:http://a.imagehost.org/0898/akka-as-library-2.png>`_

^

Actors as services with Persistence module as cache
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

As in the above, but with the addition of using the Persistence module to allow transactional persistent cache. This use case scenario you would still use a regular relational database (RDBMS) but use Akka’s transactional persistent storage as a performant scalable cache alongside the RDBMS.
`<image:http://a.imagehost.org/0230/akka-as-library-3.png>`_

^

Actors as services with Persistence module as primary storage/Service of Record (SoR)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

As in the above, but with the addition of using the Persistence module as the primary storage/SoR. In this use case you wouldn’t use a RDBMS at all but rely on one of the Akka backends (Cassandra, Terracotta, Redis, MongoDB etc.) as transactional persistent storage. This is great if have either high performance, scalability or high-availability requirements where a RDBMS would be either single point of failure or single point of bottleneck or just be too slow.
If the storage API (Maps, Vectors or Refs) is too constrained for some use cases we can bypass it and use the storage directly. However, please note that then we will lose the transactional semantics.
`<image:http://a.imagehost.org/0640/akka-as-library-4.png>`_

^

Actors as REST/Comet (push) services
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can also expose your library Actors directly as REST (`JAX <https://jersey.dev.java.net/>`_`-RS <https://jersey.dev.java.net/>`_) or Comet (`Atmosphere <https://atmosphere.dev.java.net/>`_) services by deploying the ‘AkkaServlet’ in your servlet container. In order for this to work in each define a so-called “boot” class which bootstraps the Actor configuration, wiring and startup. This is done in the ‘akka.conf’ file.
`<image:http://a.imagehost.org/0041/akka-as-library-5.png>`_

-

Using Akka as a stand alone microkernel
---------------------------------------

Akka can also be run as a stand-alone microkernel. It implements a full enterprise stack:

^

Web/REST/Comet layer
^^^^^^^^^^^^^^^^^^^^

Akka currently embeds the `Grizzly/GlassFish <https://grizzly.dev.java.net/>`_ servlet container (but will soon be pluggable with Jetty as well) which allows to build REST-based using `JAX <https://jersey.dev.java.net/>`_`-RS <https://jersey.dev.java.net/>`_ and Comet-based services using `Atmosphere <https://atmosphere.dev.java.net/>`_ as well as regular Web applications using JAX-RS’s `implicit views <http://blogs.sun.com/sandoz/entry/mvcj>`_ (see also `James Strachan’s article <http://macstrac.blogspot.com/2009/01/jax-rs-as-one-web-framework-to-rule.html>`_).

^

Service layer
^^^^^^^^^^^^^

The service layer is implemented using fault tolerant, asynchronous, throttled message passing; like `SEDA-in-a-box <http://www.eecs.harvard.edu/~mdw/proj/seda/>`_ using Actors.

Persistence layer
^^^^^^^^^^^^^^^^^

 Implemented using pluggable storage engines for both partitioned distributed massively scalable storage (like Cassandra) as well as single node storage (like MongoDB). A different storage and gives also provides different consistency/availability trade-offs implementing either Eventually Consistency (BASE) or Atomicity (ACID).

Monitoring and Management layer
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

 Providing both JMX management and monitoring as well as w3c logging.
 `<image:http://a.imagehost.org/0939/akka-as-kernel.png>`_

Use BivySack for packaging your application
-------------------------------------------

"BivySack" For Akka - SBT plugin which creates a full akka microkernel deployment for your project.

Quick and dirty SBT Plugin for creating Akka Microkernel deployments of your SBT Project. This creates a proper "akka deploy" setup with all of your dependencies and configuration files loaded, with a bootable version of your project that you can run cleanly.

Read more about it here `<http://github.com/bwmcadams/sbt-akka-bivy>`_.
