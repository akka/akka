
.. _cluster_usage:

#########
 Cluster
#########

.. note:: *This document describes how to use the features implemented so far of the 
new clustering coming in Akka Coltrane and is not available in the latest stable release.
The API might change before it is released.

For introduction to the Akka Cluster concepts please see 

Preparing your ActorSystem for Clustering
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Akka cluster is a separate jar file. Make sure that you have the following dependency in your project::

  "com.typesafe.akka" % "akka-cluster" % "2.1-SNAPSHOT"

It can be difficult to find the correct versions and repositories at the moment. The following sbt 0.11.3 build 
file illustrates what to use with Scala 2.10.0-M6 and Akka 2.1-SNAPSHOT

  import sbt._
  import sbt.Keys._

  object ProjectBuild extends Build {

    lazy val root = Project(
      id = "root",
      base = file("."),
      settings = Project.defaultSettings ++ Seq(
        name := "Akka Cluster Example",
        organization := "org.test",
        version := "0.1-SNAPSHOT",
        scalaVersion := "2.10.0-M6",
        
        resolvers += "Sonatype Releases Repo" at "https://oss.sonatype.org/content/repositories/releases/",
        resolvers += "Sonatype Snapshot Repo" at "https://oss.sonatype.org/content/repositories/snapshots/",
        resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",
        resolvers += "Typesafe Snapshots" at "http://repo.typesafe.com/typesafe/snapshots/",
        

        libraryDependencies ++= Seq(
          "com.typesafe.akka" % "akka-cluster"  % "2.1-20120816-000904",
          "com.typesafe.akka" % "akka-testkit"  % "2.1-20120816-000904"  % "test",
          "junit"             % "junit"         % "4.5"             % "test",
          "org.scalatest"    %% "scalatest"     % "1.9-2.10.0-M6-B2" % "test")
      )
    )
  }

Pick a timestamped Akka version from `<http://repo.typesafe.com/typesafe/snapshots/com/typesafe/akka/akka-cluster/>`_.

To enable cluster capabilities in your Akka project you should, at a minimum, add the :ref:`remoting-scala`
settings and the ``cluster seed-nodes`` to your ``application.conf`` file:

.. literalinclude:: ../../akka-samples/akka-sample-remote/src/main/resources/common.conf
   :language: none

The seed nodes are configured contact points for inital join of the cluster.
When a new node is started started it sends a message to all seed nodes and 
then sends join command to the one that answers first.

A Simple Cluster Example
^^^^^^^^^^^^^^^^^^^^^^^^




Configuration
^^^^^^^^^^^^^

There are lots of more properties that are related to clustering in Akka. We refer to the following
reference file for more information:


.. literalinclude:: ../../akka-cluster/src/main/resources/reference.conf
   :language: none





