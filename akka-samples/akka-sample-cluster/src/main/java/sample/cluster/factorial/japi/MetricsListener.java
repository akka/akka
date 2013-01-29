package sample.cluster.factorial.japi;

//#metrics-listener
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.ClusterMetricsChanged;
import akka.cluster.ClusterEvent.CurrentClusterState;
import akka.cluster.NodeMetrics;
import akka.cluster.StandardMetrics;
import akka.cluster.StandardMetrics.HeapMemory;
import akka.cluster.StandardMetrics.Cpu;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class MetricsListener extends UntypedActor {
  LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  Cluster cluster = Cluster.get(getContext().system());

  //subscribe to ClusterMetricsChanged
  @Override
  public void preStart() {
    cluster.subscribe(getSelf(), ClusterMetricsChanged.class);
  }

  //re-subscribe when restart
  @Override
  public void postStop() {
    cluster.unsubscribe(getSelf());
  }


  @Override
  public void onReceive(Object message) {
    if (message instanceof ClusterMetricsChanged) {
      ClusterMetricsChanged clusterMetrics = (ClusterMetricsChanged) message;
      for (NodeMetrics nodeMetrics : clusterMetrics.getNodeMetrics()) {
        if (nodeMetrics.address().equals(cluster.selfAddress())) {
          logHeap(nodeMetrics);
          logCpu(nodeMetrics);
        }
      }

    } else if (message instanceof CurrentClusterState) {
      // ignore

    } else {
      unhandled(message);
    }
  }

  void logHeap(NodeMetrics nodeMetrics) {
    HeapMemory heap = StandardMetrics.extractHeapMemory(nodeMetrics);
    if (heap != null) {
      log.info("Used heap: {} MB", ((double) heap.used()) / 1024 / 1024);
    }
  }

  void logCpu(NodeMetrics nodeMetrics) {
    Cpu cpu = StandardMetrics.extractCpu(nodeMetrics);
    if (cpu != null && cpu.systemLoadAverage().isDefined()) {
      log.info("Load: {} ({} processors)", cpu.systemLoadAverage().get(), 
        cpu.processors());
    }
  }

}
//#metrics-listener