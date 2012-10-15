
.. _multi-jvm-testing:

###################
 Multi JVM Testing
###################

Supports running applications (objects with main methods) and ScalaTest tests in multiple JVMs at the same time.
Useful for integration testing where multiple systems communicate with each other.

Setup
=====

The multi-JVM testing is an sbt plugin that you can find at `<http://github.com/typesafehub/sbt-multi-jvm>`_.

You can add it as a plugin by adding the following to your project/plugins.sbt:

.. includecode:: ../../../project/plugins.sbt#sbt-multi-jvm

You can then add multi-JVM testing to ``project/Build.scala`` by including the ``MultiJvm``
settings and config. For example, here is an example of how the akka-remote-tests project adds
multi-JVM testing (Simplified for clarity):

.. parsed-literal::

   import sbt._
   import Keys._
   import com.typesafe.sbt.SbtMultiJvm
   import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.{ MultiJvm, extraOptions }

   object AkkaBuild extends Build {

    lazy val remoteTests = Project(
      id = "akka-remote-tests",
      base = file("akka-remote-tests"),
      dependencies = Seq(remote, actorTests % "test->test", testkit % "test->test"),
      settings = defaultSettings ++ Seq(
        // disable parallel tests
        parallelExecution in Test := false,
        extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
          (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
        },
        test in Test <<= (test in Test) dependsOn (test in MultiJvm)
      )
    ) configs (MultiJvm)

    lazy val buildSettings = Defaults.defaultSettings ++ SbtMultiJvm.multiJvmSettings ++ Seq(
      organization := "com.typesafe.akka",
      version      := "2.1-SNAPSHOT",
      scalaVersion := "2.10.0-M7",
      crossPaths   := false
    )

    lazy val defaultSettings = buildSettings ++ Seq(
      resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"
    )

  }

You can specify JVM options for the forked JVMs::

    jvmOptions in MultiJvm := Seq("-Xmx256M")


Running tests
=============

The multi-jvm tasks are similar to the normal tasks: ``test``, ``test-only``,
and ``run``, but are under the ``multi-jvm`` configuration.

So in Akka, to run all the multi-JVM tests in the akka-remote project use (at
the sbt prompt):

.. code-block:: none

   akka-remote-tests/multi-jvm:test

Or one can change to the ``akka-remote-tests`` project first, and then run the
tests:

.. code-block:: none

   project akka-remote-tests
   multi-jvm:test

To run individual tests use ``test-only``:

.. code-block:: none

   multi-jvm:test-only akka.remote.RandomRoutedRemoteActor

More than one test name can be listed to run multiple specific
tests. Tab-completion in sbt makes it easy to complete the test names.

It's also possible to specify JVM options with ``test-only`` by including those
options after the test names and ``--``. For example:

.. code-block:: none

    multi-jvm:test-only akka.remote.RandomRoutedRemoteActor -- -Dsome.option=something


Creating application tests
==========================

The tests are discovered, and combined, through a naming convention. MultiJvm tests are
located in ``src/multi-jvm/scala`` directory. A test is named with the following pattern:

.. code-block:: none

    {TestName}MultiJvm{NodeName}

That is, each test has ``MultiJvm`` in the middle of its name. The part before
it groups together tests/applications under a single ``TestName`` that will run
together. The part after, the ``NodeName``, is a distinguishing name for each
forked JVM.

So to create a 3-node test called ``Sample``, you can create three applications
like the following::

    package sample

    object SampleMultiJvmNode1 {
      def main(args: Array[String]) {
        println("Hello from node 1")
      }
    }

    object SampleMultiJvmNode2 {
      def main(args: Array[String]) {
        println("Hello from node 2")
      }
    }

    object SampleMultiJvmNode3 {
      def main(args: Array[String]) {
        println("Hello from node 3")
      }
    }

When you call ``multi-jvm:run sample.Sample`` at the sbt prompt, three JVMs will be
spawned, one for each node. It will look like this:

.. code-block:: none

    > multi-jvm:run sample.Sample
    ...
    [info] Starting JVM-Node1 for sample.SampleMultiJvmNode1
    [info] Starting JVM-Node2 for sample.SampleMultiJvmNode2
    [info] Starting JVM-Node3 for sample.SampleMultiJvmNode3
    [JVM-Node1] Hello from node 1
    [JVM-Node2] Hello from node 2
    [JVM-Node3] Hello from node 3
    [success] Total time: ...


Naming
======

You can change what the ``MultiJvm`` identifier is. For example, to change it to
``ClusterTest`` use the ``multiJvmMarker`` setting::

   multiJvmMarker in MultiJvm := "ClusterTest"

Your tests should now be named ``{TestName}ClusterTest{NodeName}``.


Configuration of the JVM instances
==================================

You can define specific JVM options for each of the spawned JVMs. You do that by creating
a file named after the node in the test with suffix ``.opts`` and put them in the same
directory as the test.

For example, to feed the JVM options ``-Dakka.remote.port=9991`` to the ``SampleMultiJvmNode1``
let's create three ``*.opts`` files and add the options to them.

``SampleMultiJvmNode1.opts``::

    -Dakka.remote.port=9991

``SampleMultiJvmNode2.opts``::

    -Dakka.remote.port=9992

``SampleMultiJvmNode3.opts``::

    -Dakka.remote.port=9993

ScalaTest
=========

There is also support for creating ScalaTest tests rather than applications. To
do this use the same naming convention as above, but create ScalaTest suites
rather than objects with main methods. You need to have ScalaTest on the
classpath. Here is a similar example to the one above but using ScalaTest::

    package sample

    import org.scalatest.WordSpec
    import org.scalatest.matchers.MustMatchers

    class SpecMultiJvmNode1 extends WordSpec with MustMatchers {
      "A node" should {
        "be able to say hello" in {
          val message = "Hello from node 1"
          message must be("Hello from node 1")
        }
      }
    }

    class SpecMultiJvmNode2 extends WordSpec with MustMatchers {
      "A node" should {
        "be able to say hello" in {
          val message = "Hello from node 2"
          message must be("Hello from node 2")
        }
      }
    }

To run just these tests you would call ``multi-jvm:test-only sample.Spec`` at
the sbt prompt.

Multi Node Additions
====================

There has also been some additions made to the ``SbtMultiJvm`` plugin to accomodate the
:ref:`experimental <experimental>` module :ref:`multi node testing <multi-node-testing>`,
described in that section.
