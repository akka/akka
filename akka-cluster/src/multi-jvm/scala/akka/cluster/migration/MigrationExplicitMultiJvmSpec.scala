/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 *
 *
 * package akka.cluster.migration
 *
 * import org.scalatest.WordSpec
 * import org.scalatest.matchers.MustMatchers
 * import org.scalatest.BeforeAndAfterAll
 *
 * import akka.actor._
 * import Actor._
 * import akka.cluster._
 * import ChangeListener._
 * import Cluster._
 * import akka.config.Config
 * import akka.serialization.Serialization
 * import akka.cluster.LocalCluster._
 *
 * import java.util.concurrent._
 *
 * object MigrationExplicitMultiJvmSpec {
 * var NrOfNodes = 2
 *
 * class HelloWorld extends Actor with Serializable {
 * def receive = {
 * case "Hello" ⇒
 * reply("World from node [" + Config.nodename + "]")
 * }
 * }
 * }
 *
 * class MigrationExplicitMultiJvmNode1 extends MasterClusterTestNode {
 * import MigrationExplicitMultiJvmSpec._
 *
 * val testNodes = NrOfNodes
 *
 * "A cluster" must {
 *
 * "be able to migrate an actor from one node to another" in {
 *
 * barrier("start-node-1", NrOfNodes) {
 * Cluster.node.start()
 * }
 *
 * barrier("start-node-2", NrOfNodes) {
 * }
 *
 * barrier("store-1-in-node-1", NrOfNodes) {
 * val serializer = Serialization.serializerFor(classOf[HelloWorld]).fold(x ⇒ fail("No serializer found"), s ⇒ s)
 * node.store("hello-world", classOf[HelloWorld], serializer)
 * }
 *
 * barrier("use-1-in-node-2", NrOfNodes) {
 * }
 *
 * barrier("migrate-from-node2-to-node1", NrOfNodes) {
 * }
 *
 * barrier("check-actor-is-moved-to-node1", NrOfNodes) {
 * node.isInUseOnNode("hello-world") must be(true)
 *
 * val actorRef = Actor.registry.local.actorFor("hello-world").getOrElse(fail("Actor should have been in the local actor registry"))
 * actorRef.address must be("hello-world")
 * (actorRef ? "Hello").as[String].get must be("World from node [node1]")
 * }
 *
 * node.shutdown()
 * }
 * }
 * }
 *
 * class MigrationExplicitMultiJvmNode2 extends ClusterTestNode {
 * import MigrationExplicitMultiJvmSpec._
 *
 * "A cluster" must {
 *
 * "be able to migrate an actor from one node to another" in {
 *
 * barrier("start-node-1", NrOfNodes) {
 * }
 *
 * barrier("start-node-2", NrOfNodes) {
 * Cluster.node.start()
 * }
 *
 * barrier("store-1-in-node-1", NrOfNodes) {
 * }
 *
 * barrier("use-1-in-node-2", NrOfNodes) {
 * val actorOrOption = node.use("hello-world")
 * if (actorOrOption.isEmpty) fail("Actor could not be retrieved")
 *
 * val actorRef = actorOrOption.get
 * actorRef.address must be("hello-world")
 *
 * (actorRef ? "Hello").as[String].get must be("World from node [node2]")
 * }
 *
 * barrier("migrate-from-node2-to-node1", NrOfNodes) {
 * node.migrate(NodeAddress(node.nodeAddress.clusterName, "node1"), "hello-world")
 * Thread.sleep(2000)
 * }
 *
 * barrier("check-actor-is-moved-to-node1", NrOfNodes) {
 * }
 *
 * node.shutdown()
 * }
 * }
 * }
 */
