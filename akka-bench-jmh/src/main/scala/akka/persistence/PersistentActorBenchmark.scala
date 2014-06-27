/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence

import org.openjdk.jmh.annotations._
import org.openjdk.jmh._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.testkit.TestProbe
import java.io.File
import org.apache.commons.io.FileUtils
import org.openjdk.jmh.annotations.Scope

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
class PersistentActorThroughputBenchmark {

  val config = PersistenceSpec.config("leveldb", "benchmark")

  lazy val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s ⇒ new File(system.settings.config.getString(s)))

  var system: ActorSystem = _

  var probe: TestProbe = _
  var actor: ActorRef = _
  var persist1EventProcessor: ActorRef = _
  var persist1CommandProcessor: ActorRef = _
  var persistAsync1EventProcessor: ActorRef = _
  var persistAsync1QuickReplyEventProcessor: ActorRef = _

  val data10k = (1 to 10000).toArray

  @Setup
  def setup() {
    system = ActorSystem("test", config)

    probe = TestProbe()(system)

    storageLocations.foreach(FileUtils.deleteDirectory)
    actor = system.actorOf(Props(classOf[BaselineActor], data10k.last), "a-1")
    persist1CommandProcessor = system.actorOf(Props(classOf[Persist1EventPersistentActor], data10k.last), "p-1")
    persist1EventProcessor = system.actorOf(Props(classOf[Persist1EventPersistentActor], data10k.last), "ep-1")
    persistAsync1EventProcessor = system.actorOf(Props(classOf[PersistAsync1EventPersistentActor], data10k.last), "epa-1")
    persistAsync1QuickReplyEventProcessor = system.actorOf(Props(classOf[PersistAsync1EventQuickReplyPersistentActor], data10k.last), "epa-2")
  }

  @TearDown
  def shutdown() {
    system.shutdown()
    system.awaitTermination()

    storageLocations.foreach(FileUtils.deleteDirectory)
  }

  @GenerateMicroBenchmark
  @OperationsPerInvocation(10000)
  def tell_normalActor_reply_baseline() {
    for (i <- data10k) actor.tell(i, probe.ref)

    probe.expectMsg(data10k.last)
  }

  @GenerateMicroBenchmark
  @OperationsPerInvocation(10000)
  def tell_persist_reply() {
    for (i <- data10k) persist1EventProcessor.tell(i, probe.ref)

    probe.expectMsg(Evt(data10k.last))
  }

  @GenerateMicroBenchmark
  @OperationsPerInvocation(10000)
  def tell_commandPersist_reply() {
    for (i <- data10k) persist1CommandProcessor.tell(i, probe.ref)

    probe.expectMsg(Evt(data10k.last))
  }

  @GenerateMicroBenchmark
  @OperationsPerInvocation(10000)
  def tell_persistAsync_reply() {
    for (i <- data10k) persistAsync1EventProcessor.tell(i, probe.ref)

    probe.expectMsg(Evt(data10k.last))
  }

  @GenerateMicroBenchmark
  @OperationsPerInvocation(10000)
  def tell_persistAsync_replyRightOnCommandReceive() {
    for (i <- data10k) persistAsync1QuickReplyEventProcessor.tell(i, probe.ref)

    probe.expectMsg(Evt(data10k.last))
  }

}

class Persist1EventPersistentActor(respondAfter: Int) extends PersistentActor {

  override def persistenceId: String = self.path.name

  override def receiveCommand = {
    case n: Int => persist(Evt(n)) { e => if (e.i == respondAfter) sender() ! e }
  }
  override def receiveRecover = {
    case _ => // do nothing
  }

}
class Persist1CommandProcessor(respondAfter: Int) extends Processor {
  override def receive = {
    case n: Int => if (n == respondAfter) sender() ! Evt(n)
  }
}

class PersistAsync1EventPersistentActor(respondAfter: Int) extends PersistentActor {
  override def persistenceId: String = self.path.name

  override def receiveCommand = {
    case n: Int =>
      persistAsync(Evt(n)) { e => if (e.i == respondAfter) sender() ! e }
  }
  override def receiveRecover = {
    case _ => // do nothing
  }
}

class PersistAsync1EventQuickReplyPersistentActor(respondAfter: Int) extends PersistentActor {

  override def persistenceId: String = self.path.name

  override def receiveCommand = {
    case n: Int =>
      val e = Evt(n)
      if (n == respondAfter) sender() ! e
      persistAsync(e)(identity)
  }
  override def receiveRecover = {
    case _ => // do nothing
  }
}
