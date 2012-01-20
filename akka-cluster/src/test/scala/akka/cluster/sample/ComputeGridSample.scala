/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.sample

import akka.cluster._
import akka.dispatch.Futures

object ComputeGridSample {
  //sample.cluster.ComputeGridSample.fun2

  // FIXME rewrite as multi-jvm test

  /*
  // run all
  def run {
    fun1
    fun2
    fun3
    fun4
  }

  // Send Function0[Unit]
  def fun1 = {
    Cluster.startLocalCluster()
    val node = Cluster newNode (NodeAddress("test", "local", port = 9991)) start
    val remote1 = Cluster newNode (NodeAddress("test", "remote1", port = 9992)) start

    Thread.sleep(100)
    val fun = () ⇒ println("=============>>> AKKA ROCKS <<<=============")
    node send (fun, 2) // send and invoke function on to two cluster nodes

    node.stop
    remote1.stop
    Cluster.shutdownLocalCluster()
  }

  // Send Function0[Any]
  def fun2 = {
    Cluster.startLocalCluster()
    val local = Cluster newNode (NodeAddress("test", "local", port = 9991)) start
    val remote1 = Cluster newNode (NodeAddress("test", "remote1", port = 9992)) start

    Thread.sleep(100)
    val fun = () ⇒ "AKKA ROCKS"
    val futures = local send (fun, 2) // send and invoke function on to two cluster nodes and get result

    val result = Await.sync(Futures.fold("")(futures)(_ + " - " + _), timeout)
    println("===================>>> Cluster says [" + result + "]")

    local.stop
    remote1.stop
    Cluster.shutdownLocalCluster()
  }

  // Send Function1[Any, Unit]
  def fun3 = {
    Cluster.startLocalCluster()
    val local = Cluster newNode (NodeAddress("test", "local", port = 9991)) start
    val remote1 = Cluster newNode (NodeAddress("test", "remote1", port = 9992)) start

    val fun = ((s: String) ⇒ println("=============>>> " + s + " <<<=============")).asInstanceOf[Function1[Any, Unit]]
    local send (fun, "AKKA ROCKS", 2) // send and invoke function on to two cluster nodes

    local.stop
    remote1.stop
    Cluster.shutdownLocalCluster()
  }

  // Send Function1[Any, Any]
  def fun4 = {
    Cluster.startLocalCluster()
    val local = Cluster newNode (NodeAddress("test", "local", port = 9991)) start
    val remote1 = Cluster newNode (NodeAddress("test", "remote1", port = 9992)) start

    val fun = ((i: Int) ⇒ i * i).asInstanceOf[Function1[Any, Any]]

    val future1 = local send (fun, 2, 1) head // send and invoke function on one cluster node and get result
    val future2 = local send (fun, 2, 1) head // send and invoke function on one cluster node and get result

    // grab the result from the first one that returns
    val result = Await.sync(Futures.firstCompletedOf(List(future1, future2)), timeout)
    println("===================>>> Cluster says [" + result + "]")

    local.stop
    remote1.stop
    Cluster.shutdownLocalCluster()
  }
  */
}
