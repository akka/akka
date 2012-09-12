
.. _cluster_usage:

###############
 Cluster Usage
###############

.. note:: This module is :ref:`experimental <experimental>`. This document describes how to use the features implemented so far. More features are coming in Akka Coltrane. Track progress of the Coltrane milestone in `Assembla <http://www.assembla.com/spaces/akka/tickets>`_ and the `Roadmap <https://docs.google.com/document/d/18W9-fKs55wiFNjXL9q50PYOnR7-nnsImzJqHOPPbM4E/edit?hl=en_US>`_.

For introduction to the Akka Cluster concepts please see :ref:`cluster`.

Preparing Your Project for Clustering
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Akka cluster is a separate jar file. Make sure that you have the following dependency in your project:

.. parsed-literal::

  "com.typesafe.akka" %% "akka-cluster" % "2.1-SNAPSHOT"

If you are using the latest nightly build you should pick a timestamped Akka version from `<http://repo.typesafe.com/typesafe/snapshots/com/typesafe/akka/akka-cluster/>`_.

A Simple Cluster Example
^^^^^^^^^^^^^^^^^^^^^^^^

The following small program together with its configuration starts an ``ActorSystem`` 
with the Cluster extension enabled. It joins the cluster and logs some membership events.

Try it out:

1. Add the following ``application.conf`` in your project, place it in ``src/main/resources``:


.. literalinclude:: ../../akka-samples/akka-sample-cluster/src/main/resources/application.conf
   :language: none

To enable cluster capabilities in your Akka project you should, at a minimum, add the :ref:`remoting-scala`
settings and the ``akka.cluster.seed-nodes`` to your ``application.conf`` file.

The seed nodes are configured contact points for initial, automatic, join of the cluster.

Note that if you are going to start the nodes on different machines you need to specify the
ip-addresses or host names of the machines in ``application.conf`` instead of ``127.0.0.1``

2. Add the following main program to your project, place it in ``src/main/scala``:

.. literalinclude:: ../../akka-samples/akka-sample-cluster/src/main/scala/sample/cluster/ClusterApp.scala   
   :language: scala


3. Start the first seed node. Open a sbt session in one terminal window and run::

  run-main sample.cluster.ClusterApp 2551

2551 corresponds to the port of the first seed-nodes element in the configuration.
In the log output you see that the cluster node has been started and changed status to 'Up'.

4. Start the second seed node. Open a sbt session in another terminal window and run::

  run-main sample.cluster.ClusterApp 2552


2552 corresponds to the port of the second seed-nodes element in the configuration.
In the log output you see that the cluster node has been started and joins the other seed node
and becomes a member of the cluster. It's status changed to 'Up'.

Switch over to the first terminal window and see in the log output that the member joined.

5. Start another node. Open a sbt session in yet another terminal window and run::

  run-main sample.cluster.ClusterApp

Now you don't need to specify the port number, and it will use a random available port.
It joins one of the configured seed nodes. Look at the log output in the different terminal
windows.

Start even more nodes in the same way, if you like.

6. Shut down one of the nodes by pressing 'ctrl-c' in one of the terminal windows.
The other nodes will detect the failure after a while, which you can see in the log
output in the other terminals.

Look at the source code of the program again. What it does is to create an actor
and register it as subscriber of certain cluster events. It gets notified with 
an snapshot event, 'CurrentClusterState' that holds full state information of
the cluster. After that it receives events for changes that happen in the cluster.

Automatic vs. Manual Joining
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You may decide if joining to the cluster should be done automatically or manually.
By default it is automatic and you need to define the seed nodes in configuration
so that a new node has an initial contact point. When a new node is started it 
sends a message to all seed nodes and then sends join command to the one that 
answers first. If no one of the seed nodes replied (might not be started yet)
it retries this procedure until successful or shutdown.

There is one thing to be aware of regarding the seed node configured as the 
first element in the ``seed-nodes`` configuration list.
The seed nodes can be started in any order and it is not necessary to have all
seed nodes running, but the first seed node must be started when initially 
starting a cluster, otherwise the other seed-nodes will not become initialized 
and no other node can join the cluster. Once more than two seed nodes have been 
started it is no problem to shut down the first seed node. If it goes down it 
must be manually joined to the cluster again. 
Automatic joining of the first seed node is not possible, it would only join 
itself. It is only the first seed node that has this restriction.

You can disable automatic joining with configuration:

  akka.cluster.auto-join = off

Then you need to join manually, using JMX or the provided script. 
You can join to any node in the cluster. It doesn't have to be configured as
seed node. If you are not using auto-join there is no need to configure 
seed nodes at all.

Joining can also be performed programatically with ``Cluster(system).join``.


Automatic vs. Manual Downing
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

When a member is considered by the failure detector to be unreachable the
leader is not allowed to perform its duties, such as changing status of
new joining members to 'Up'. The status of the unreachable member must be
changed to 'Down'. This can be performed automatically or manually. By 
default it must be done manually, using using JMX or the provided script.

It can also be performed programatically with ``Cluster(system).down``.

You can enable automatic downing with configuration:

  akka.cluster.auto-down = on

Be aware of that using auto-down implies that two separate clusters will 
automatically be formed in case of network partition. That might be 
desired by some applications but not by others. 

Configuration
^^^^^^^^^^^^^

There are several configuration properties for the cluster. We refer to the following
reference file for more information:


.. literalinclude:: ../../akka-cluster/src/main/resources/reference.conf
   :language: none

It is recommended that you change the ``tick-duration`` to 33 ms or less
of the default scheduler when using cluster, if you don't need to have it 
configured to a longer duration for other reasons. If you don't do this
a dedicated scheduler will be used for periodic tasks of the cluster, which
introduce the extra overhead of another thread.

::

  # shorter tick-duration of default scheduler when using cluster
  akka.scheduler.tick-duration.tick-duration = 33ms



