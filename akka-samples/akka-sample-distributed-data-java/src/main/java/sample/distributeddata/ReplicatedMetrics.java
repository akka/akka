package sample.distributeddata;

import static akka.cluster.ddata.Replicator.writeLocal;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import scala.concurrent.duration.FiniteDuration;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Address;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent;
import akka.cluster.ClusterEvent.MemberRemoved;
import akka.cluster.ClusterEvent.MemberUp;
import akka.cluster.ddata.DistributedData;
import akka.cluster.ddata.Key;
import akka.cluster.ddata.LWWMap;
import akka.cluster.ddata.LWWMapKey;
import akka.cluster.ddata.Replicator.Changed;
import akka.cluster.ddata.Replicator.Subscribe;
import akka.cluster.ddata.Replicator.Update;
import akka.cluster.ddata.Replicator.UpdateResponse;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;

@SuppressWarnings("unchecked")
public class ReplicatedMetrics extends AbstractActor {

  public static Props props(FiniteDuration measureInterval, FiniteDuration cleanupInterval) {
    return Props.create(ReplicatedMetrics.class, measureInterval, cleanupInterval);
  }

  public static class UsedHeap {
    public Map<String, Double> percentPerNode;

    public UsedHeap(Map<String, Double> percentPerNode) {
      this.percentPerNode = percentPerNode;
    }
  }

  private static final String TICK = "tick";
  private static final String CLEANUP = "cleanup";

  public static String nodeKey(Address address) {
    return address.host().get() + ":" + address.port().get();
  }

  private final ActorRef replicator = DistributedData.get(context().system()).replicator();
  private final Cluster node = Cluster.get(context().system());
  private final String selfNodeKey = nodeKey(node.selfAddress());
  private final MemoryMXBean memoryMBean = ManagementFactory.getMemoryMXBean();
  private final LoggingAdapter log = Logging.getLogger(context().system(), this);

  private final Key<LWWMap<Long>> usedHeapKey = LWWMapKey.create("usedHeap");
  private final Key<LWWMap<Long>> maxHeapKey = LWWMapKey.create("maxHeap");

  private final Cancellable tickTask;
  private final Cancellable cleanupTask;

  private Map<String, Long> maxHeap = new HashMap<>();
  private final Set<String> nodesInCluster = new HashSet<>();

  @Override
  public void preStart() {
    replicator.tell(new Subscribe<>(maxHeapKey, self()), ActorRef.noSender());
    replicator.tell(new Subscribe<>(usedHeapKey, self()), ActorRef.noSender());
    node.subscribe(self(), ClusterEvent.initialStateAsEvents(),
        MemberUp.class, MemberRemoved.class);
  }

  @Override
  public void postStop() throws Exception {
    tickTask.cancel();
    cleanupTask.cancel();
    node.unsubscribe(self());
    super.postStop();
  }

  public ReplicatedMetrics(FiniteDuration measureInterval, FiniteDuration cleanupInterval) {
    tickTask = context().system().scheduler().schedule(measureInterval, measureInterval,
        self(), TICK, context().dispatcher(), self());
    cleanupTask = context().system().scheduler().schedule(cleanupInterval, cleanupInterval,
        self(), CLEANUP, context().dispatcher(), self());

    receive(ReceiveBuilder
      .matchEquals(TICK, t -> receiveTick())
      .match(Changed.class, c -> c.key().equals(maxHeapKey), c -> receiveMaxHeapChanged((Changed<LWWMap<Long>>) c))
      .match(Changed.class, c -> c.key().equals(usedHeapKey), c -> receiveUsedHeapChanged((Changed<LWWMap<Long>>) c))
      .match(UpdateResponse.class, u -> {})
      .match(MemberUp.class, m -> receiveMemberUp(m.member().address()))
      .match(MemberRemoved.class, m -> receiveMemberRemoved(m.member().address()))
      .matchEquals(CLEANUP, c -> receiveCleanup())
      .build());
  }

  private void receiveTick() {
    MemoryUsage heap = memoryMBean.getHeapMemoryUsage();
    long used = heap.getUsed();
    long max = heap.getMax();

    Update<LWWMap<Long>> update1 = new Update<>(usedHeapKey, LWWMap.create(), writeLocal(),
        curr -> curr.put(node, selfNodeKey, used));
    replicator.tell(update1, self());

    Update<LWWMap<Long>> update2 = new Update<>(maxHeapKey, LWWMap.create(), writeLocal(), curr -> {
      if (curr.contains(selfNodeKey) && curr.get(selfNodeKey).get().longValue() == max)
        return curr; // unchanged
      else
        return curr.put(node, selfNodeKey, max);
    });
    replicator.tell(update2, self());
  }

  private void receiveMaxHeapChanged(Changed<LWWMap<Long>> c) {
    maxHeap = c.dataValue().getEntries();
  }

  private void receiveUsedHeapChanged(Changed<LWWMap<Long>> c) {
    Map<String, Double> percentPerNode = new HashMap<>();
    for (Map.Entry<String, Long> entry : c.dataValue().getEntries().entrySet()) {
      if (maxHeap.containsKey(entry.getKey())) {
        double percent = (entry.getValue().doubleValue() / maxHeap.get(entry.getKey())) * 100.0;
        percentPerNode.put(entry.getKey(), percent);
      }
    }
    UsedHeap usedHeap = new UsedHeap(percentPerNode);
    log.debug("Node {} observed:\n{}", node, usedHeap);
    context().system().eventStream().publish(usedHeap);
  }

  private void receiveMemberUp(Address address) {
    nodesInCluster.add(nodeKey(address));
  }

  private void receiveMemberRemoved(Address address) {
    nodesInCluster.remove(nodeKey(address));
    if (address.equals(node.selfAddress()))
      context().stop(self());
  }

  private void receiveCleanup() {
    Update<LWWMap<Long>> update1 = new Update<>(usedHeapKey, LWWMap.create(), writeLocal(), curr -> cleanup(curr));
    replicator.tell(update1, self());
    Update<LWWMap<Long>> update2 = new Update<>(maxHeapKey, LWWMap.create(), writeLocal(), curr -> cleanup(curr));
    replicator.tell(update2, self());
  }

  private LWWMap<Long> cleanup(LWWMap<Long> data) {
    LWWMap<Long> result = data;
    log.info("Cleanup " + nodesInCluster + " -- " + data.getEntries().keySet());
    for (String k : data.getEntries().keySet()) {
      if (!nodesInCluster.contains(k)) {
        result = result.remove(node, k);
      }
    }
    return result;
  }

}