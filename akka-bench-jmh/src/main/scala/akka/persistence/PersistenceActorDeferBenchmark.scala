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

/*
  # OS:   OSX 10.9.3
  # CPU:  Intel(R) Core(TM) i7-4850HQ CPU @ 2.30GHz
  # Date: Mon Jun  9 13:22:42 CEST 2014

  [info] Benchmark                                                                            Mode   Samples         Mean   Mean error    Units
  [info] a.p.PersistentActorDeferBenchmark.tell_persistAsync_defer_persistAsync_reply        thrpt        10        6.858        0.515   ops/ms
  [info] a.p.PersistentActorDeferBenchmark.tell_persistAsync_defer_persistAsync_replyASAP    thrpt        10       20.256        2.941   ops/ms
  [info] a.p.PersistentActorDeferBenchmark.tell_processor_Persistent_reply                   thrpt        10        6.531        0.114   ops/ms
  [info] a.p.PersistentActorDeferBenchmark.tell_processor_Persistent_replyASAP               thrpt        10       26.000        0.694   ops/ms
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
class PersistentActorDeferBenchmark {

  val config = PersistenceSpec.config("leveldb", "benchmark")

  lazy val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s ⇒ new File(system.settings.config.getString(s)))

  var system: ActorSystem = _

  var probe: TestProbe = _
  var processor: ActorRef = _
  var processor_replyASAP: ActorRef = _
  var persistAsync_defer: ActorRef = _
  var persistAsync_defer_replyASAP: ActorRef = _

  val data10k = (1 to 10000).toArray

  @Setup
  def setup() {
    system = ActorSystem("test", config)

    probe = TestProbe()(system)

    storageLocations.foreach(FileUtils.deleteDirectory)
    processor = system.actorOf(Props(classOf[`processor, forward Persistent, like defer`], data10k.last), "p-1")
    processor_replyASAP = system.actorOf(Props(classOf[`processor, forward Persistent, reply ASAP`], data10k.last), "p-2")
    persistAsync_defer = system.actorOf(Props(classOf[`persistAsync, defer`], data10k.last), "a-1")
    persistAsync_defer_replyASAP = system.actorOf(Props(classOf[`persistAsync, defer, respond ASAP`], data10k.last), "a-2")
  }

  @TearDown
  def shutdown() {
    system.shutdown()
    system.awaitTermination()

    storageLocations.foreach(FileUtils.deleteDirectory)
  }

  @Benchmark
  @OperationsPerInvocation(10000)
  def tell_processor_Persistent_reply() {
    for (i ← data10k) processor.tell(i, probe.ref)

    probe.expectMsg(data10k.last)
  }

  @Benchmark
  @OperationsPerInvocation(10000)
  def tell_processor_Persistent_replyASAP() {
    for (i ← data10k) processor_replyASAP.tell(i, probe.ref)

    probe.expectMsg(data10k.last)
  }

  @Benchmark
  @OperationsPerInvocation(10000)
  def tell_persistAsync_defer_persistAsync_reply() {
    for (i ← data10k) persistAsync_defer.tell(i, probe.ref)

    probe.expectMsg(data10k.last)
  }

  @Benchmark
  @OperationsPerInvocation(10000)
  def tell_persistAsync_defer_persistAsync_replyASAP() {
    for (i ← data10k) persistAsync_defer_replyASAP.tell(i, probe.ref)

    probe.expectMsg(data10k.last)
  }

}

class `processor, forward Persistent, like defer`(respondAfter: Int) extends Processor {
  def receive = {
    case n: Int ⇒
      self forward Persistent(Evt(n))
      self forward Evt(n)
    case Persistent(p)               ⇒ // ignore
    case Evt(n) if n == respondAfter ⇒ sender() ! respondAfter
  }
}
class `processor, forward Persistent, reply ASAP`(respondAfter: Int) extends Processor {
  def receive = {
    case n: Int ⇒
      self forward Persistent(Evt(n))
      if (n == respondAfter) sender() ! respondAfter
    case _ ⇒ // ignore
  }
}

class `persistAsync, defer`(respondAfter: Int) extends PersistentActor {

  override def persistenceId: String = self.path.name

  override def receiveCommand = {
    case n: Int ⇒
      persistAsync(Evt(n)) { e ⇒ }
      defer(Evt(n)) { e ⇒ if (e.i == respondAfter) sender() ! e.i }
  }
  override def receiveRecover = {
    case _ ⇒ // do nothing
  }
}
class `persistAsync, defer, respond ASAP`(respondAfter: Int) extends PersistentActor {

  override def persistenceId: String = self.path.name

  override def receiveCommand = {
    case n: Int ⇒
      persistAsync(Evt(n)) { e ⇒ }
      defer(Evt(n)) { e ⇒ }
      if (n == respondAfter) sender() ! n
  }
  override def receiveRecover = {
    case _ ⇒ // do nothing
  }
}
