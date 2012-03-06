.. _configuration:

Configuration
=============

Akka uses the `Typesafe Config Library
<https://github.com/typesafehub/config>`_, which might also be a good choice
for the configuration of your own application or library built with or without
Akka. This library is implemented in Java with no external dependencies; you
should have a look at its documentation (in particular about `ConfigFactory
<http://typesafehub.github.com/config/latest/api/com/typesafe/config/ConfigFactory.html>`_),
which is only summarized in the following.

Where configuration is read from
--------------------------------

All configuration for Akka is held within instances of :class:`ActorSystem`, or
put differently, as viewed from the outside, :class:`ActorSystem` is the only
consumer of configuration information. While constructing an actor system, you
can either pass in a :class:`Config` object or not, where the second case is
equivalent to passing ``ConfigFactory.load()`` (with the right class loader).
This means roughly that the default is to parse all ``application.conf``,
``application.json`` and ``application.properties`` found at the root of the
class path—please refer to the aforementioned documentation for details. The
actor system then merges in all ``reference.conf`` resources found at the root
of the class path to form the fallback configuration, i.e. it internally uses

.. code-block:: scala

  appConfig.withFallback(ConfigFactory.defaultReference(classLoader))
  
The philosophy is that code never contains default values, but instead relies
upon their presence in the ``reference.conf`` supplied with the library in
question.

Highest precedence is given to overrides given as system properties, see `the
HOCON specification
<https://github.com/typesafehub/config/blob/master/HOCON.md>`_ (near the
bottom). Also noteworthy is that the application configuration—which defaults
to ``application``—may be overridden using the ``config.resource`` property
(there are more, please refer to the `Config docs
<https://github.com/typesafehub/config/blob/master/README.md>`_).

.. note::

  If you are writing an Akka application, keep you configuration in
  ``application.conf`` at the root of the class path. If you are writing an
  Akka-based library, keep its configuration in ``reference.conf`` at the root
  of the JAR file.

How to structure your configuration
-----------------------------------

Given that ``ConfigFactory.load()`` merges all resources with matching name
from the whole class path, it is easiest to utilize that functionality and
differenciate actor systems within the hierarchy of the configuration::

  myapp1 {
    akka.loglevel = WARNING
    my.own.setting = 43
  }
  myapp2 {
    akka.loglevel = ERROR
    app2.setting = "appname"
  }
  my.own.setting = 42
  my.other.setting = "hello"

.. code-block:: scala

  val config = ConfigFactory.load()
  val app1 = ActorSystem("MyApp1", config.getConfig("myapp1").withFallback(config))
  val app2 = ActorSystem("MyApp2", config.getConfig("myapp2").withOnlyPath("akka").withFallback(config))

These two samples demonstrate different variations of the “lift-a-subtree”
trick: in the first case, the configuration accessible from within the actor
system is this

.. code-block:: ruby

  akka.loglevel = WARNING
  my.own.setting = 43
  my.other.setting = "hello"
  // plus myapp1 and myapp2 subtrees

while in the second one, only the “akka” subtree is lifted, with the following
result::

  akka.loglevel = ERROR
  my.own.setting = 42
  my.other.setting = "hello"
  // plus myapp1 and myapp2 subtrees

.. note::

  The configuration library is really powerful, explaining all features exceeds
  the scope affordable here. In particular not covered are how to include other
  configuration files within other files (see a small example at `Including
  files`_) and copying parts of the configuration tree by way of path
  substitutions.

You may also specify and parse the configuration programmatically in other ways when instantiating
the ``ActorSystem``.

.. includecode:: code/akka/docs/config/ConfigDocSpec.scala
   :include: imports,custom-config

Listing of the Reference Configuration
--------------------------------------

Each Akka module has a reference configuration file with the default values.

akka-actor
~~~~~~~~~~

.. literalinclude:: ../../akka-actor/src/main/resources/reference.conf
   :language: none

akka-remote
~~~~~~~~~~~

.. literalinclude:: ../../akka-remote/src/main/resources/reference.conf
   :language: none

akka-testkit
~~~~~~~~~~~~

.. literalinclude:: ../../akka-testkit/src/main/resources/reference.conf
   :language: none

akka-transactor
~~~~~~~~~~~~~~~

.. literalinclude:: ../../akka-transactor/src/main/resources/reference.conf
   :language: none

akka-agent
~~~~~~~~~~

.. literalinclude:: ../../akka-agent/src/main/resources/reference.conf
   :language: none

akka-zeromq
~~~~~~~~~~~

.. literalinclude:: ../../akka-zeromq/src/main/resources/reference.conf
   :language: none

akka-beanstalk-mailbox
~~~~~~~~~~~~~~~~~~~~~~

.. literalinclude:: ../../akka-durable-mailboxes/akka-beanstalk-mailbox/src/main/resources/reference.conf
   :language: none

akka-file-mailbox
~~~~~~~~~~~~~~~~~

.. literalinclude:: ../../akka-durable-mailboxes/akka-file-mailbox/src/main/resources/reference.conf
   :language: none

akka-mongo-mailbox
~~~~~~~~~~~~~~~~~~

.. literalinclude:: ../../akka-durable-mailboxes/akka-mongo-mailbox/src/main/resources/reference.conf
   :language: none

akka-redis-mailbox
~~~~~~~~~~~~~~~~~~

.. literalinclude:: ../../akka-durable-mailboxes/akka-redis-mailbox/src/main/resources/reference.conf
   :language: none

akka-zookeeper-mailbox
~~~~~~~~~~~~~~~~~~~~~~

.. literalinclude:: ../../akka-durable-mailboxes/akka-zookeeper-mailbox/src/main/resources/reference.conf
   :language: none

Custom application.conf
-----------------------

A custom ``application.conf`` might look like this::

  # In this file you can override any option defined in the reference files.
  # Copy in parts of the reference files and modify as you please.

  akka {

    # Event handlers to register at boot time (Logging$DefaultLogger logs to STDOUT)
    event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]

    # Log level used by the configured loggers (see "event-handlers") as soon
    # as they have been started; before that, see "stdout-loglevel"
    # Options: ERROR, WARNING, INFO, DEBUG
    loglevel = DEBUG

    # Log level for the very basic logger activated during AkkaApplication startup
    # Options: ERROR, WARNING, INFO, DEBUG
    stdout-loglevel = DEBUG

    actor {
      default-dispatcher {
        # Throughput for default Dispatcher, set to 1 for as fair as possible
        throughput = 10
      }
    }

    remote {
      server {
        # The port clients should connect to. Default is 2552 (AKKA)
        port = 2562
      }
    }
  }


Including files
---------------

Sometimes it can be useful to include another configuration file, for example if you have one ``application.conf`` with all
environment independent settings and then override some settings for specific environments.

Specifying system property with ``-Dconfig.resource=/dev.conf`` will load the ``dev.conf`` file, which includes the ``application.conf``

dev.conf:

::

  include "application"

  akka {
    loglevel = "DEBUG"
  }

More advanced include and substitution mechanisms are explained in the `HOCON <https://github.com/typesafehub/config/blob/master/HOCON.md>`_
specification.


.. _-Dakka.log-config-on-start:

Logging of Configuration
------------------------

If the system or config property ``akka.log-config-on-start`` is set to ``on``, then the
complete configuration at INFO level when the actor system is started. This is useful
when you are uncertain of what configuration is used.

If in doubt, you can also easily and nicely inspect configuration objects
before or after using them to construct an actor system:

.. code-block:: scala

  Welcome to Scala version 2.9.1.final (Java HotSpot(TM) 64-Bit Server VM, Java 1.6.0_27).
  Type in expressions to have them evaluated.
  Type :help for more information.

  scala> import com.typesafe.config._
  import com.typesafe.config._

  scala> ConfigFactory.parseString("a.b=12")
  res0: com.typesafe.config.Config = Config(SimpleConfigObject({"a" : {"b" : 12}}))

  scala> res0.root.render
  res1: java.lang.String =
  {
      # String: 1
      "a" : {
          # String: 1
          "b" : 12
      }
  }

The comments preceding every item give detailed information about the origin of
the setting (file & line number) plus possible comments which were present,
e.g. in the reference configuration. The settings as merged with the reference
and parsed by the actor system can be displayed like this:

.. code-block:: java

  final ActorSystem system = ActorSystem.create();
  println(system.settings());
  // this is a shortcut for system.settings().config().root().render()

A Word About ClassLoaders
-------------------------

In several places of the configuration file it is possible to specify the
fully-qualified class name of something to be instantiated by Akka. This is
done using Java reflection, which in turn uses a :class:`ClassLoader`. Getting
the right one in challenging environments like application containers or OSGi
bundles is not always trivial, the current approach of Akka is that each
:class:`ActorSystem` implementation stores the current thread’s context class
loader (if available, otherwise just its own loader as in
``this.getClass.getClassLoader``) and uses that for all reflective accesses.
This implies that putting Akka on the boot class path will yield
:class:`NullPointerException` from strange places: this is simply not
supported.

Application specific settings
-----------------------------

The configuration can also be used for application specific settings.
A good practice is to place those settings in an Extension, as described in:

 * Scala API: :ref:`extending-akka-scala.settings`
 * Java API: :ref:`extending-akka-java.settings`
