Cluster Sample
==============

This sample is meant to be used by studying the code; it does not perform any
astounding functions when running it. If you want to run it, check out the akka
sources on your local hard drive, follow the [instructions for setting up Akka
with SBT](http://doc.akka.io/docs/akka/current/intro/getting-started.html).
When you start SBT within the checked-out akka source directory, you can run
this sample by typing

    akka-sample-cluster-experimental/run-main sample.cluster.simple.SimpleClusterApp 2551

and then from another terminal start more cluster nodes like this:

    akka-sample-cluster-experimental/run-main sample.cluster.simple.SimpleClusterApp

Then you can start and stop cluster nodes and observe the messages printed by
the remaining ones, demonstrating cluster membership changes.

You can read more in the [Akka docs](http://akka.io/docs).
