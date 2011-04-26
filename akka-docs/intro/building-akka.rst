Building Akka
=============

This page describes how to build and run Akka from the latest source code.

.. contents:: :local:


Get the source code
-------------------

Akka uses `Git <http://git-scm.com>`_ and is hosted at `Github
<http://github.com>`_.

You first need Git installed on your machine. You can then clone the source
repositories:

- Akka repository from `<http://github.com/jboner/akka>`_
- Akka Modules repository from `<http://github.com/jboner/akka-modules>`_

For example::

   git clone git://github.com/jboner/akka.git
   git clone git://github.com/jboner/akka-modules.git

If you have already cloned the repositories previously then you can update the
code with ``git pull``::

   git pull origin master


SBT - Simple Build Tool
-----------------------

Akka is using the excellent `SBT <http://code.google.com/p/simple-build-tool>`_
build system. So the first thing you have to do is to download and install
SBT. You can read more about how to do that `here
<http://code.google.com/p/simple-build-tool/wiki/Setup>`_ .

The SBT commands that you'll need to build Akka are all included below. If you
want to find out more about SBT and using it for your own projects do read the
`SBT documentation
<http://code.google.com/p/simple-build-tool/wiki/RunningSbt>`_.

The Akka SBT build file is ``project/build/AkkaProject.scala`` with some
properties defined in ``project/build.properties``.


Building Akka
-------------

First make sure that you are in the akka code directory::

   cd akka


Fetching dependencies
^^^^^^^^^^^^^^^^^^^^^

SBT does not fetch dependencies automatically. You need to manually do this with
the ``update`` command::

   sbt update

Once finished, all the dependencies for Akka will be in the ``lib_managed``
directory under each module: akka-actor, akka-stm, and so on.

*Note: you only need to run update the first time you are building the code,
or when the dependencies have changed.*


Building
^^^^^^^^

To compile all the Akka core modules use the ``compile`` command::

   sbt compile

You can run all tests with the ``test`` command::

   sbt test

If compiling and testing are successful then you have everything working for the
latest Akka development version.


Publish to local Ivy repository
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

If you want to deploy the artifacts to your local Ivy repository (for example,
to use from an SBT project) use the ``publish-local`` command::

   sbt publish-local


Publish to local Maven repository
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

If you want to deploy the artifacts to your local Maven repository use::

   sbt publish-local publish


SBT interactive mode
^^^^^^^^^^^^^^^^^^^^

Note that in the examples above we are calling ``sbt compile`` and ``sbt test``
and so on. SBT also has an interactive mode. If you just run ``sbt`` you enter
the interactive SBT prompt and can enter the commands directly. This saves
starting up a new JVM instance for each command and can be much faster and more
convenient.

For example, building Akka as above is more commonly done like this:

.. code-block:: none

   % sbt
   [info] Building project akka 1.1-SNAPSHOT against Scala 2.9.0.RC1
   [info]    using AkkaParentProject with sbt 0.7.6.RC0 and Scala 2.7.7
   > update
   [info]
   [info] == akka-actor / update ==
   ...
   [success] Successful.
   [info]
   [info] Total time ...
   > compile
   ...
   > test
   ...


SBT batch mode
^^^^^^^^^^^^^^

It's also possible to combine commands in a single call. For example, updating,
testing, and publishing Akka to the local Ivy repository can be done with::

   sbt update test publish-local


Building Akka Modules
---------------------

To build Akka Modules first build and publish Akka to your local Ivy repository
as described above. Or using::

   cd akka
   sbt update publish-local

Then you can build Akka Modules using the same steps as building Akka. First
update to get all dependencies (including the Akka core modules), then compile,
test, or publish-local as needed. For example::

   cd akka-modules
   sbt update publish-local


Microkernel distribution
^^^^^^^^^^^^^^^^^^^^^^^^

To build the Akka Modules microkernel (the same as the Akka Modules distribution
download) use the ``dist`` command::

   sbt dist

The distribution zip can be found in the dist directory and is called
``akka-modules-{version}.zip``.

To run the microkernel, unzip the zip file, change into the unzipped directory,
set the ``AKKA_HOME`` environment variable, and run the main jar file. For
example:

.. code-block:: none

   unzip dist/akka-modules-1.1-SNAPSHOT.zip
   cd akka-modules-1.1-SNAPSHOT
   export AKKA_HOME=`pwd`
   java -jar akka-modules-1.1-SNAPSHOT.jar

The microkernel will boot up and install the sample applications that reside in
the distribution's ``deploy`` directory. You can deploy your own applications
into the ``deploy`` directory as well.


Scripts
-------

Linux/Unix init script
^^^^^^^^^^^^^^^^^^^^^^

Here is a Linux/Unix init script that can be very useful:

http://github.com/jboner/akka/blob/master/scripts/akka-init-script.sh

Copy and modify as needed.


Simple startup shell script
^^^^^^^^^^^^^^^^^^^^^^^^^^^

This little script might help a bit. Just make sure you have the Akka
distribution in the '$AKKA_HOME/dist' directory and then invoke this script to
start up the kernel. The distribution is created in the './dist' dir for you if
you invoke 'sbt dist'.

http://github.com/jboner/akka/blob/master/scripts/run_akka.sh

Copy and modify as needed.


Dependencies
------------

If you are managing dependencies by hand you can find out what all the compile
dependencies are for each module by looking in the ``lib_managed/compile``
directories. For example, you can run this to create a listing of dependencies
(providing you have the source code and have run ``sbt update``)::

   cd akka
   ls -1 */lib_managed/compile


Dependencies used by the Akka core modules
------------------------------------------

akka-actor
^^^^^^^^^^

* No dependencies

akka-stm
^^^^^^^^

* Depends on akka-actor
* multiverse-alpha-0.6.2.jar

akka-typed-actor
^^^^^^^^^^^^^^^^

* Depends on akka-stm
* aopalliance-1.0.jar
* aspectwerkz-2.2.3.jar
* guice-all-2.0.jar

akka-remote
^^^^^^^^^^^

* Depends on akka-typed-actor
* commons-codec-1.4.jar
* commons-io-2.0.1.jar
* dispatch-json_2.8.1-0.7.8.jar
* guice-all-2.0.jar
* h2-lzf-1.0.jar
* jackson-core-asl-1.7.1.jar
* jackson-mapper-asl-1.7.1.jar
* junit-4.8.1.jar
* netty-3.2.3.Final.jar
* objenesis-1.2.jar
* protobuf-java-2.3.0.jar
* sjson_2.8.1-0.9.1.jar

akka-http
^^^^^^^^^

* Depends on akka-remote
* jsr250-api-1.0.jar
* jsr311-api-1.1.jar


Dependencies used by the Akka modules
-------------------------------------

akka-amqp
^^^^^^^^^

* Depends on akka-remote
* commons-cli-1.1.jar
* amqp-client-1.8.1.jar

akka-camel
^^^^^^^^^^

* Depends on akka-actor
* camel-core-2.7.0.jar
* commons-logging-api-1.1.jar
* commons-management-1.0.jar

akka-camel-typed
^^^^^^^^^^^^^^^^

* Depends on akka-typed-actor
* camel-core-2.7.0.jar
* commons-logging-api-1.1.jar
* commons-management-1.0.jar

akka-spring
^^^^^^^^^^^

* Depends on akka-camel
* akka-camel-typed
* commons-logging-1.1.1.jar
* spring-aop-3.0.4.RELEASE.jar
* spring-asm-3.0.4.RELEASE.jar
* spring-beans-3.0.4.RELEASE.jar
* spring-context-3.0.4.RELEASE.jar
* spring-core-3.0.4.RELEASE.jar
* spring-expression-3.0.4.RELEASE.jar

akka-scalaz
^^^^^^^^^^^

* Depends on akka-actor
* hawtdispatch-1.1.jar
* hawtdispatch-scala-1.1.jar
* scalaz-core_2.8.1-6.0-SNAPSHOT.jar

akka-kernel
^^^^^^^^^^^

* Depends on akka-http, akka-amqp, and akka-spring
* activation-1.1.jar
* asm-3.1.jar
* jaxb-api-2.1.jar
* jaxb-impl-2.1.12.jar
* jersey-core-1.3.jar
* jersey-json-1.3.jar
* jersey-scala-1.3.jar
* jersey-server-1.3.jar
* jettison-1.1.jar
* jetty-continuation-7.1.6.v20100715.jar
* jetty-http-7.1.6.v20100715.jar
* jetty-io-7.1.6.v20100715.jar
* jetty-security-7.1.6.v20100715.jar
* jetty-server-7.1.6.v20100715.jar
* jetty-servlet-7.1.6.v20100715.jar
* jetty-util-7.1.6.v20100715.jar
* jetty-xml-7.1.6.v20100715.jar
* servlet-api-2.5.jar
* stax-api-1.0.1.jar
