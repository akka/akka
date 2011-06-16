Multi-JVM Testing
=================

Included in the example is an sbt trait for multi-JVM testing which will fork
JVMs for multi-node testing. There is support for running applications (objects
with main methods) and running ScalaTest tests.

Using the multi-JVM testing is straight-forward. First, mix the ``MultiJvmTests``
trait into your sbt project::

    class SomeProject(info: ProjectInfo) extends DefaultProject(info) with MultiJvmTests

You can specify JVM options for the forked JVMs::

    class SomeProject(info: ProjectInfo) extends DefaultProject(info) with MultiJvmTests {
      override def multiJvmOptions = Seq("-Xmx256M")
    }

There are two sbt commands: ``multi-jvm-run`` for running applications and
``multi-jvm-test`` for running ScalaTest tests.

The ``MultiJvmTests`` trait resides in the ``project/build`` directory.

Creating application tests
~~~~~~~~~~~~~~~~~~~~~~~~~~

The tests are discovered through a naming convention. A test is named with the
following pattern:

.. code-block:: none

    {TestName}MultiJvm{NodeName}

That is, each test has ``MultiJvm`` in the middle of its name. The part before
it groups together tests/applications under a single ``TestName`` that will run
together. The part after, the ``NodeName``, is a distinguishing name for each
forked JVM.

So to create a 3-node test called ``Test``, you can create three applications
like the following::

    package example

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

When you call ``multi-jvm-run Test`` at the sbt prompt, three JVMs will be
spawned, one for each node. It will look like this:

.. code-block:: none

    > multi-jvm-run Test
    ...
    [info] == multi-jvm-run ==
    [info] == multi-jvm / Test ==
    [info] Starting JVM-Node1 for example.SampleMultiJvmNode1
    [info] Starting JVM-Node2 for example.SampleMultiJvmNode2
    [info] Starting JVM-Node3 for example.SampleMultiJvmNode3
    [JVM-Node1] Hello from node 1
    [JVM-Node2] Hello from node 2
    [JVM-Node3] Hello from node 3
    [info] == multi-jvm / Test ==
    [info] == multi-jvm-run ==
    [success] Successful.


Naming
~~~~~~

You can change what the ``MultiJvm`` identifier is. For example, to change it to
``ClusterTest`` override the ``multiJvmTestName`` method::

    class SomeProject(info: ProjectInfo) extends DefaultProject(info) with MultiJvmTests {
      override def multiJvmTestName = "ClusterSpec"
    }

Your tests should now be named ``{TestName}ClusterTest{NodeName}``.

Configuration of the JVM instances
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Setting JVM options
-------------------

You can define specific JVM options for each of the spawned JVMs. You do that by creating
a file named after the node in the test with suffix ``.opts`` and put them in the same
directory as the test.

For example, to feed the JVM options ``-Dakka.cluster.nodename=node1`` and
``-Dakka.cluster.port=9991`` to the ``SampleMultiJvmNode1`` let's create three ``*.opts`` files
and add the options to them.

``SampleMultiJvmNode1.opts``::

    -Dakka.cluster.nodename=node1 -Dakka.cluster.port=9991

``SampleMultiJvmNode2.opts``::

    -Dakka.cluster.nodename=node2 -Dakka.cluster.port=9992

``SampleMultiJvmNode3.opts``::

    -Dakka.cluster.nodename=node3 -Dakka.cluster.port=9993

Overriding akka.conf options
----------------------------

You can also override the options in the ``akka.conf`` file with different options for each
spawned JVM. You do that by creating a file named after the node in the test with suffix
``.conf`` and put them in the same  directory as the test .

For example, to override the configuration option ``akka.cluster.name`` let's create three
``*.conf`` files and add the option to them.

``SampleMultiJvmNode1.conf``::

    akka.cluster.name = "test-cluster"

``SampleMultiJvmNode2.conf``::

    akka.cluster.name = "test-cluster"

``SampleMultiJvmNode3.conf``::

    akka.cluster.name = "test-cluster"

ScalaTest
~~~~~~~~~

There is also support for creating ScalaTest tests rather than applications. To
do this use the same naming convention as above, but create ScalaTest suites
rather than objects with main methods. You need to have ScalaTest on the
classpath. Here is a similar example to the one above but using ScalaTest::

    package example

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

To run these tests you would call ``multi-jvm-test Spec`` at the sbt prompt.


Zookeeper Barrier
~~~~~~~~~~~~~~~~~

When running multi-JVM tests it's common to need to coordinate timing across
nodes. To do this there is a ZooKeeper-based double-barrier (there is both an
entry barrier and an exit barrier). ClusterNodes also have support for creating
barriers easily. To wait at the entry use the ``enter`` method. To wait at the
exit use the ``leave`` method. It's also possible t pass a block of code which
will be run between the barriers.

When creating a barrier you pass it a name and the number of nodes that are
expected to arrive at the barrier. You can also pass a timeout. The default
timeout is 60 seconds.

Here is an example of coordinating the starting of two nodes and then running
something in coordination::

    import org.scalatest.WordSpec
    import org.scalatest.matchers.MustMatchers
    import org.scalatest.BeforeAndAfterAll

    import akka.cluster._

    object SampleMultiJvmSpec {
      val NrOfNodes = 2
    }

    class SampleMultiJvmNode1 extends WordSpec with MustMatchers with BeforeAndAfterAll {
      import SampleMultiJvmSpec._

      override def beforeAll() = {
        Cluster.startLocalCluster()
      }

      override def afterAll() = {
        Cluster.shutdownLocalCluster()
      }

      "A cluster" must {

        "have jvm options" in {
          System.getProperty("akka.cluster.nodename", "") must be("node1")
          System.getProperty("akka.cluster.port", "") must be("9991")
          akka.config.Config.config.getString("test.name", "") must be("node1")
        }

        "be able to start all nodes" in {
          Cluster.barrier("start", NrOfNodes) {
            Cluster.node.start()
          }
          Cluster.node.isRunning must be(true)
          Cluster.node.shutdown()
        }
      }
    }

    class SampleMultiJvmNode2 extends WordSpec with MustMatchers {
      import SampleMultiJvmSpec._

      "A cluster" must {

        "have jvm options" in {
          System.getProperty("akka.cluster.nodename", "") must be("node2")
          System.getProperty("akka.cluster.port", "") must be("9992")
          akka.config.Config.config.getString("test.name", "") must be("node2")
        }

        "be able to start all nodes" in {
          Cluster.barrier("start", NrOfNodes) {
            Cluster.node.start()
          }
          Cluster.node.isRunning must be(true)
          Cluster.node.shutdown()
        }
      }
    }

An example output from this would be:

.. code-block:: none

    > multi-jvm-run Test
    ...
    [info] == multi-jvm-run ==
    [info] == multi-jvm / Test ==
    [info] Starting JVM-Node1 for example.SampleMultiJvmNode1
    [info] Starting JVM-Node2 for example.SampleMultiJvmNode2
    [JVM-Node1] Loading config [akka.conf] from the application classpath.
    [JVM-Node2] Loading config [akka.conf] from the application classpath.
    ...
    [JVM-Node2] Hello from node 2
    [JVM-Node1] Hello from node 1
    [info] == multi-jvm / Test ==
    [info] == multi-jvm-run ==
    [success] Successful.

NetworkFailureTest
^^^^^^^^^^^^^^^^^^

You can use the 'NetworkFailureTest' trait to test network failure. See the 'RemoteErrorHandlingNetworkTest' test. Your tests needs to end with 'NetworkTest'. They are disabled by default. To run them you need to enable a flag.

Example:

::

  project akka-remote
  set akka.test.network true
  test-only akka.actor.remote.RemoteErrorHandlingNetworkTest

It uses 'ipfw' for network management. Mac OSX comes with it installed but if you are on another platform you might need to install it yourself. Here is a port:

`<http://info.iet.unipi.it/~luigi/dummynet>`_
