.. _configuration:

Configuration
=============

You can start using Akka without defining any configuration, since sensible default values
are provided. Later on you might need to amend the settings to change the default behavior
or adapt for specific runtime environments. Typical examples of settings that you
might amend:

* log level and logger backend
* enable remoting
* message serializers
* definition of routers
* tuning of dispatchers

Akka uses the `Typesafe Config Library
<https://github.com/typesafehub/config>`_, which might also be a good choice
for the configuration of your own application or library built with or without
Akka. This library is implemented in Java with no external dependencies; you
should have a look at its documentation (in particular about `ConfigFactory
<http://typesafehub.github.io/config/v1.2.0/com/typesafe/config/ConfigFactory.html>`_),
which is only summarized in the following.

.. warning::

   If you use Akka from the Scala REPL from the 2.9.x series,
   and you do not provide your own ClassLoader to the ActorSystem,
   start the REPL with "-Yrepl-sync" to work around a deficiency in
   the REPLs provided Context ClassLoader.


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


When using JarJar, OneJar, Assembly or any jar-bundler
------------------------------------------------------

.. warning::

    Akka's configuration approach relies heavily on the notion of every
    module/jar having its own reference.conf file, all of these will be
    discovered by the configuration and loaded. Unfortunately this also means
    that if you put/merge multiple jars into the same jar, you need to merge all the
    reference.confs as well. Otherwise all defaults will be lost and Akka will not function.


If you are using Maven to package your application, you can also make use of
the `Apache Maven Shade Plugin
<http://maven.apache.org/plugins/maven-shade-plugin>`_ support for `Resource
Transformers
<http://maven.apache.org/plugins/maven-shade-plugin/examples/resource-transformers.html#AppendingTransformer>`_
to merge all the reference.confs on the build classpath into one. 

The plugin configuration might look like this::

    <plugin>
     <groupId>org.apache.maven.plugins</groupId>
     <artifactId>maven-shade-plugin</artifactId>
     <version>1.5</version>
     <executions>
      <execution>
       <phase>package</phase>
       <goals>
        <goal>shade</goal>
       </goals>
       <configuration>
        <shadedArtifactAttached>true</shadedArtifactAttached>
        <shadedClassifierName>allinone</shadedClassifierName>
        <artifactSet>
         <includes>
          <include>*:*</include>
         </includes>
        </artifactSet>
        <transformers>
          <transformer
           implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
           <resource>reference.conf</resource>
          </transformer>
          <transformer
           implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
           <manifestEntries>
            <Main-Class>akka.Main</Main-Class>
           </manifestEntries>
          </transformer>
        </transformers>
       </configuration>
      </execution>
     </executions>
    </plugin>

Custom application.conf
-----------------------

A custom ``application.conf`` might look like this::

  # In this file you can override any option defined in the reference files.
  # Copy in parts of the reference files and modify as you please.

  akka {

    # Loggers to register at boot time (akka.event.Logging$DefaultLogger logs
    # to STDOUT)
    loggers = ["akka.event.slf4j.Slf4jLogger"]

    # Log level used by the configured loggers (see "loggers") as soon
    # as they have been started; before that, see "stdout-loglevel"
    # Options: OFF, ERROR, WARNING, INFO, DEBUG
    loglevel = "DEBUG"

    # Log level for the very basic logger activated during ActorSystem startup.
    # This logger prints the log messages to stdout (System.out).
    # Options: OFF, ERROR, WARNING, INFO, DEBUG
    stdout-loglevel = "DEBUG"

    actor {
      provider = "akka.cluster.ClusterActorRefProvider"
      
      default-dispatcher {
        # Throughput for default Dispatcher, set to 1 for as fair as possible
        throughput = 10
      }
    }

    remote {
      # The port clients should connect to. Default is 2552.
      netty.tcp.port = 4711
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

.. parsed-literal::

  Welcome to Scala version @scalaVersion@ (Java HotSpot(TM) 64-Bit Server VM, Java 1.6.0_27).
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
  System.out.println(system.settings());
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

Configuring multiple ActorSystem
--------------------------------

If you have more than one ``ActorSystem`` (or you're writing a
library and have an ``ActorSystem`` that may be separate from the
application's) you may want to separate the configuration for each
system.

Given that ``ConfigFactory.load()`` merges all resources with matching name
from the whole class path, it is easiest to utilize that functionality and
differentiate actor systems within the hierarchy of the configuration::

  myapp1 {
    akka.loglevel = "WARNING"
    my.own.setting = 43
  }
  myapp2 {
    akka.loglevel = "ERROR"
    app2.setting = "appname"
  }
  my.own.setting = 42
  my.other.setting = "hello"

.. code-block:: scala

  val config = ConfigFactory.load()
  val app1 = ActorSystem("MyApp1", config.getConfig("myapp1").withFallback(config))
  val app2 = ActorSystem("MyApp2",
    config.getConfig("myapp2").withOnlyPath("akka").withFallback(config))

These two samples demonstrate different variations of the “lift-a-subtree”
trick: in the first case, the configuration accessible from within the actor
system is this

.. code-block:: ruby

  akka.loglevel = "WARNING"
  my.own.setting = 43
  my.other.setting = "hello"
  // plus myapp1 and myapp2 subtrees

while in the second one, only the “akka” subtree is lifted, with the following
result

.. code-block:: ruby

  akka.loglevel = "ERROR"
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

.. includecode:: code/docs/config/ConfigDocSpec.scala
   :include: imports,custom-config

Reading configuration from a custom location
--------------------------------------------

You can replace or supplement ``application.conf`` either in code
or using system properties.

If you're using ``ConfigFactory.load()`` (which Akka does by
default) you can replace ``application.conf`` by defining
``-Dconfig.resource=whatever``, ``-Dconfig.file=whatever``, or
``-Dconfig.url=whatever``.

From inside your replacement file specified with
``-Dconfig.resource`` and friends, you can ``include
"application"`` if you still want to use
``application.{conf,json,properties}`` as well.  Settings
specified before ``include "application"`` would be overridden by
the included file, while those after would override the included
file.

In code, there are many customization options.

There are several overloads of ``ConfigFactory.load()``; these
allow you to specify something to be sandwiched between system
properties (which override) and the defaults (from
``reference.conf``), replacing the usual
``application.{conf,json,properties}`` and replacing
``-Dconfig.file`` and friends.

The simplest variant of ``ConfigFactory.load()`` takes a resource
basename (instead of ``application``); ``myname.conf``,
``myname.json``, and ``myname.properties`` would then be used
instead of ``application.{conf,json,properties}``.

The most flexible variant takes a ``Config`` object, which
you can load using any method in ``ConfigFactory``.  For example
you could put a config string in code using
``ConfigFactory.parseString()`` or you could make a map and
``ConfigFactory.parseMap()``, or you could load a file.

You can also combine your custom config with the usual config,
that might look like:

.. includecode:: code/docs/config/ConfigDoc.java
   :include: java-custom-config

When working with ``Config`` objects, keep in mind that there are
three "layers" in the cake:

 - ``ConfigFactory.defaultOverrides()`` (system properties)
 - the app's settings
 - ``ConfigFactory.defaultReference()`` (reference.conf)

The normal goal is to customize the middle layer while leaving the
other two alone.

 - ``ConfigFactory.load()`` loads the whole stack
 - the overloads of ``ConfigFactory.load()`` let you specify a
   different middle layer
 - the ``ConfigFactory.parse()`` variations load single files or resources

To stack two layers, use ``override.withFallback(fallback)``; try
to keep system props (``defaultOverrides()``) on top and
``reference.conf`` (``defaultReference()``) on the bottom.

Do keep in mind, you can often just add another ``include``
statement in ``application.conf`` rather than writing code.
Includes at the top of ``application.conf`` will be overridden by
the rest of ``application.conf``, while those at the bottom will
override the earlier stuff.

Actor Deployment Configuration
------------------------------

Deployment settings for specific actors can be defined in the ``akka.actor.deployment``
section of the configuration. In the deployment section it is possible to define
things like dispatcher, mailbox, router settings, and remote deployment.
Configuration of these features are described in the chapters detailing corresponding
topics. An example may look like this:

.. includecode:: code/docs/config/ConfigDocSpec.scala#deployment-section

The deployment section for a specific actor is identified by the 
path of the actor relative to ``/user``.

You can use asterisks as wildcard matches for the actor path sections, so you could specify:
``/*/sampleActor`` and that would match all ``sampleActor`` on that level in the hierarchy.
You can also use wildcard in the last position to match all actors at a certain level:
``/someParent/*``. Non-wildcard matches always have higher priority to match than wildcards, so:
``/foo/bar`` is considered **more specific** than ``/foo/*`` and only the highest priority match is used.
Please note that it **cannot** be used to partially match section, like this: ``/foo*/bar``, ``/f*o/bar`` etc.

Listing of the Reference Configuration
--------------------------------------

Each Akka module has a reference configuration file with the default values.

.. _config-akka-actor:

akka-actor
~~~~~~~~~~

.. literalinclude:: ../../../akka-actor/src/main/resources/reference.conf
   :language: none

.. _config-akka-agent:

akka-agent
~~~~~~~~~~

.. literalinclude:: ../../../akka-agent/src/main/resources/reference.conf
   :language: none

.. _config-akka-camel:

akka-camel
~~~~~~~~~~

.. literalinclude:: ../../../akka-camel/src/main/resources/reference.conf
   :language: none

.. _config-akka-cluster:

akka-cluster
~~~~~~~~~~~~

.. literalinclude:: ../../../akka-cluster/src/main/resources/reference.conf
   :language: none

.. _config-akka-multi-node-testkit:

akka-multi-node-testkit
~~~~~~~~~~~~~~~~~~~~~~~

.. literalinclude:: ../../../akka-multi-node-testkit/src/main/resources/reference.conf
   :language: none

.. _config-akka-persistence:

akka-persistence
~~~~~~~~~~~~~~~~

.. literalinclude:: ../../../akka-persistence/src/main/resources/reference.conf
   :language: none

.. _config-akka-remote:

akka-remote
~~~~~~~~~~~

.. literalinclude:: ../../../akka-remote/src/main/resources/reference.conf
   :language: none

.. _config-akka-testkit:

akka-testkit
~~~~~~~~~~~~

.. literalinclude:: ../../../akka-testkit/src/main/resources/reference.conf
   :language: none

.. _config-akka-zeromq:

akka-zeromq
~~~~~~~~~~~

.. literalinclude:: ../../../akka-zeromq/src/main/resources/reference.conf
   :language: none


