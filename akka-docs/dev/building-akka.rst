
.. highlightlang:: none

.. _building-akka:

###############
 Building Akka
###############

This page describes how to build and run Akka from the latest source code.


Get the source code
===================

Akka uses `Git`_ and is hosted at `Github`_.

.. _Git: http://git-scm.com
.. _Github: http://github.com

You first need Git installed on your machine. You can then clone the source
repository from http://github.com/akka/akka.

For example::

   git clone git://github.com/akka/akka.git

If you have already cloned the repository previously then you can update the
code with ``git pull``::

   git pull origin master


sbt - Simple Build Tool
=======================

Akka is using the excellent `sbt`_ build system. So the first thing you have to
do is to download and install sbt. You can read more about how to do that in the
`sbt setup`_ documentation.

.. _sbt: https://github.com/harrah/xsbt
.. _sbt setup: https://github.com/harrah/xsbt/wiki/Getting-Started-Setup

The sbt commands that you'll need to build Akka are all included below. If you
want to find out more about sbt and using it for your own projects do read the
`sbt documentation`_.

.. _sbt documentation: https://github.com/harrah/xsbt/wiki

The Akka sbt build file is ``project/AkkaBuild.scala``.


Building Akka
=============

First make sure that you are in the akka code directory::

   cd akka


Building
--------

To compile all the Akka core modules use the ``compile`` command::

   sbt compile

You can run all tests with the ``test`` command::

   sbt test

If compiling and testing are successful then you have everything working for the
latest Akka development version.


Parallel Execution
------------------

By default the tests are executed sequentially. They can be executed in parallel to reduce build times,
if hardware can handle the increased memory and cpu usage. Add the following system property to sbt
launch script to activate parallel execution::

  -Dakka.parallelExecution=true

Publish to local Ivy repository
-------------------------------

If you want to deploy the artifacts to your local Ivy repository (for example,
to use from an sbt project) use the ``publish-local`` command::

   sbt publish-local


sbt interactive mode
--------------------

Note that in the examples above we are calling ``sbt compile`` and ``sbt test``
and so on, but sbt also has an interactive mode. If you just run ``sbt`` you
enter the interactive sbt prompt and can enter the commands directly. This saves
starting up a new JVM instance for each command and can be much faster and more
convenient.

For example, building Akka as above is more commonly done like this::

   % sbt
   [info] Set current project to default (in build file:/.../akka/project/plugins/)
   [info] Set current project to akka (in build file:/.../akka/)
   > compile
   ...
   > test
   ...


sbt batch mode
--------------

It's also possible to combine commands in a single call. For example, testing,
and publishing Akka to the local Ivy repository can be done with::

   sbt test publish-local


.. _dependencies:

Dependencies
============

You can look at the Ivy dependency resolution information that is created on
``sbt update`` and found in ``~/.ivy2/cache``. For example, the
``~/.ivy2/cache/com.typesafe.akka-akka-remote-compile.xml`` file contains
the resolution information for the akka-remote module compile dependencies. If
you open this file in a web browser you will get an easy to navigate view of
dependencies.
