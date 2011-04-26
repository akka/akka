
.. highlightlang:: none

.. _building-akka:

###############
 Building Akka
###############

This page describes how to build and run Akka from the latest source code.

.. contents:: :local:


Get the source code
===================

Akka uses `Git`_ and is hosted at `Github`_.

.. _Git: http://git-scm.com
.. _Github: http://github.com

You first need Git installed on your machine. You can then clone the source
repositories:

- Akka repository from http://github.com/jboner/akka
- Akka Modules repository from http://github.com/jboner/akka-modules

For example::

   git clone git://github.com/jboner/akka.git
   git clone git://github.com/jboner/akka-modules.git

If you have already cloned the repositories previously then you can update the
code with ``git pull``::

   git pull origin master


SBT - Simple Build Tool
=======================

Akka is using the excellent `SBT`_ build system. So the first thing you have to
do is to download and install SBT. You can read more about how to do that in the
`SBT setup`_ documentation.

.. _SBT: http://code.google.com/p/simple-build-tool
.. _SBT setup: http://code.google.com/p/simple-build-tool/wiki/Setup

The SBT commands that you'll need to build Akka are all included below. If you
want to find out more about SBT and using it for your own projects do read the
`SBT documentation`_.

.. _SBT documentation: http://code.google.com/p/simple-build-tool/wiki/RunningSbt

The Akka SBT build file is ``project/build/AkkaProject.scala`` with some
properties defined in ``project/build.properties``.


Building Akka
=============

First make sure that you are in the akka code directory::

   cd akka


Fetching dependencies
---------------------

SBT does not fetch dependencies automatically. You need to manually do this with
the ``update`` command::

   sbt update

Once finished, all the dependencies for Akka will be in the ``lib_managed``
directory under each module: akka-actor, akka-stm, and so on.

*Note: you only need to run update the first time you are building the code,
or when the dependencies have changed.*


Building
--------

To compile all the Akka core modules use the ``compile`` command::

   sbt compile

You can run all tests with the ``test`` command::

   sbt test

If compiling and testing are successful then you have everything working for the
latest Akka development version.


Publish to local Ivy repository
-------------------------------

If you want to deploy the artifacts to your local Ivy repository (for example,
to use from an SBT project) use the ``publish-local`` command::

   sbt publish-local


Publish to local Maven repository
---------------------------------

If you want to deploy the artifacts to your local Maven repository use::

   sbt publish-local publish


SBT interactive mode
--------------------

Note that in the examples above we are calling ``sbt compile`` and ``sbt test``
and so on. SBT also has an interactive mode. If you just run ``sbt`` you enter
the interactive SBT prompt and can enter the commands directly. This saves
starting up a new JVM instance for each command and can be much faster and more
convenient.

For example, building Akka as above is more commonly done like this::

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
--------------

It's also possible to combine commands in a single call. For example, updating,
testing, and publishing Akka to the local Ivy repository can be done with::

   sbt update test publish-local


Building Akka Modules
=====================

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
------------------------

To build the Akka Modules microkernel (the same as the Akka Modules distribution
download) use the ``dist`` command::

   sbt dist

The distribution zip can be found in the dist directory and is called
``akka-modules-{version}.zip``.

To run the microkernel, unzip the zip file, change into the unzipped directory,
set the ``AKKA_HOME`` environment variable, and run the main jar file. For
example::

   unzip dist/akka-modules-1.1-SNAPSHOT.zip
   cd akka-modules-1.1-SNAPSHOT
   export AKKA_HOME=`pwd`
   java -jar akka-modules-1.1-SNAPSHOT.jar

The microkernel will boot up and install the sample applications that reside in
the distribution's ``deploy`` directory. You can deploy your own applications
into the ``deploy`` directory as well.


Scripts
=======

Linux/Unix init script
----------------------

Here is a Linux/Unix init script that can be very useful:

http://github.com/jboner/akka/blob/master/scripts/akka-init-script.sh

Copy and modify as needed.


Simple startup shell script
---------------------------

This little script might help a bit. Just make sure you have the Akka
distribution in the '$AKKA_HOME/dist' directory and then invoke this script to
start up the kernel. The distribution is created in the './dist' dir for you if
you invoke 'sbt dist'.

http://github.com/jboner/akka/blob/master/scripts/run_akka.sh

Copy and modify as needed.


Dependencies
============

If you are managing dependencies by hand you can find the dependencies for each
module by looking in the ``lib_managed`` directories. For example, this will
list all compile dependencies (providing you have the source code and have run
``sbt update``)::

   cd akka
   ls -1 */lib_managed/compile

You can also look at the Ivy dependency resolution information that is created
on ``sbt update`` and found in ``~/.ivy2/cache``. For example, the
``.ivy2/cache/se.scalablesolutions.akka-akka-remote-compile.xml`` file contains
the resolution information for the akka-remote module compile dependencies. If
you open this file in a web browser you will get an easy to navigate view of
dependencies.
