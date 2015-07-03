package sample.distributeddata;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Address;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent;
import akka.cluster.ddata.DistributedData;
import akka.cluster.ddata.GSet;
import akka.cluster.ddata.GSetKey;
import akka.cluster.ddata.Key;
import akka.cluster.ddata.ORSet;
import akka.cluster.ddata.Replicator;
import akka.cluster.ddata.Replicator.Changed;
import akka.cluster.ddata.Replicator.Subscribe;
import akka.cluster.ddata.Replicator.Update;
import akka.cluster.ddata.Replicator.UpdateResponse;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;

@SuppressWarnings("unchecked")
public class ServiceRegistry extends AbstractActor {

  /**
   * Register a `service` with a `name`. Several services can be registered with
   * the same `name`. It will be removed when it is terminated.
   */
  public static class Register {
    public final String name;
    public final ActorRef service;

    public Register(String name, ActorRef service) {
      this.name = name;
      this.service = service;
    }
  }

  /**
   * Lookup services registered for a `name`. {@link Bindings} will be sent to
   * `sender()`.
   */
  public static class Lookup {
    public final String name;

    public Lookup(String name) {
      this.name = name;
    }
  }

  /**
   * Reply for {@link Lookup}
   */
  public static class Bindings {
    public final String name;
    public final Set<ActorRef> services;

    public Bindings(String name, Set<ActorRef> services) {
      this.name = name;
      this.services = services;
    }
  }

  /**
   * Published to `ActorSystem.eventStream` when services are changed.
   */
  public static class BindingChanged {
    public final String name;
    public final Set<ActorRef> services;

    public BindingChanged(String name, Set<ActorRef> services) {
      this.name = name;
      this.services = services;
    }
  }

  public static class ServiceKey extends Key<ORSet<ActorRef>> {
    private static final long serialVersionUID = 1L;

    public ServiceKey(String serviceName) {
      super(serviceName);
    }
  }

  public static Props props() {
    return Props.create(ServiceRegistry.class);
  }

  private final LoggingAdapter log = Logging.getLogger(context().system(), this);
  private final ActorRef replicator = DistributedData.get(context().system()).replicator();
  private final Cluster node = Cluster.get(context().system());


  private final Key<GSet<ServiceKey>> allServicesKey = GSetKey.create("service-keys");

  private Set<ServiceKey> keys = new HashSet<>();
  private final Map<String, Set<ActorRef>> services = new HashMap<>();
  private boolean leader = false;

  public ServiceRegistry() {
    receive(matchCommands()
        .orElse(matchChanged())
        .orElse(matchWatch())
        .orElse(matchOther()));
  }

  @Override
  public void preStart() {
    replicator.tell(new Subscribe<>(allServicesKey, self()), ActorRef.noSender());
    node.subscribe(self(), ClusterEvent.initialStateAsEvents(), ClusterEvent.LeaderChanged.class);
  }

  @Override
  public void postStop() throws Exception {
    node.unsubscribe(self());
    super.postStop();
  }

  private PartialFunction<Object, BoxedUnit> matchCommands() {
    return ReceiveBuilder
      .match(Register.class, r -> receiveRegister(r))
      .match(Lookup.class, l -> receiveLookup(l))
      .build();
  }

  private ServiceKey serviceKey(String serviceName) {
    return new ServiceKey("service:" + serviceName);
  }


  private void receiveRegister(Register r) {
    ServiceKey dKey = serviceKey(r.name);
    // store the service names in a separate GSet to be able to
    // get notifications of new names
    if (!keys.contains(dKey)) {
      Update<GSet<ServiceKey>> update1 = new Update<>(allServicesKey, GSet.create(), Replicator.writeLocal(),
          curr -> curr.add(dKey));
      replicator.tell(update1, self());
    }

    Update<ORSet<ActorRef>> update2 = new Update<>(dKey, ORSet.create(), Replicator.writeLocal(),
        curr -> curr.add(node, r.service));
    replicator.tell(update2, self());
  }

  private void receiveLookup(Lookup l) {
    sender().tell(new Bindings(l.name, services.getOrDefault(l.name, Collections.emptySet())), self());
  }

  private PartialFunction<Object, BoxedUnit> matchChanged() {
    return ReceiveBuilder
      .match(Changed.class, c -> {
        if (c.key().equals(allServicesKey))
        receiveAllServicesKeysChanged((Changed<GSet<ServiceKey>>) c);
        else if (c.key() instanceof ServiceKey)
        receiveServiceChanged((Changed<ORSet<ActorRef>>) c);
      })
      .build();
  }

  private void receiveAllServicesKeysChanged(Changed<GSet<ServiceKey>> c) {
    Set<ServiceKey> newKeys = c.dataValue().getElements();
    Set<ServiceKey> diff = new HashSet<>(newKeys);
        diff.removeAll(keys);
        log.debug("Services changed, added: {}, all: {}", diff, newKeys);
        diff.forEach(dKey -> {
          // subscribe to get notifications of when services with this name are added or removed
          replicator.tell(new Subscribe<ORSet<ActorRef>>(dKey, self()), self());
        });
    keys = newKeys;

  }

  private void receiveServiceChanged(Changed<ORSet<ActorRef>> c) {
    String name = c.key().id().split(":")[1];
    Set<ActorRef> newServices = c.get(serviceKey(name)).getElements();
    log.debug("Services changed for name [{}]: {}", name, newServices);
    services.put(name, newServices);
    context().system().eventStream().publish(new BindingChanged(name, newServices));
    if (leader) {
      newServices.forEach(ref -> context().watch(ref)); // watch is idempotent
    }
  }

  private PartialFunction<Object, BoxedUnit> matchWatch() {
    return ReceiveBuilder
        .match(ClusterEvent.LeaderChanged.class, c -> c.getLeader() != null,
          c -> receiveLeaderChanged(c.getLeader()))
        .match(Terminated.class, t -> receiveTerminated(t.actor()))
        .build();
  }

  private void receiveLeaderChanged(Address newLeader) {
    // Let one node (the leader) be responsible for removal of terminated services
    // to avoid redundant work and too many death watch notifications.
    // It is not critical to only do it from one node.
    boolean wasLeader = leader;
    leader = newLeader.equals(node.selfAddress());
    // when used with many (> 500) services you must increase the system message buffer
    // `akka.remote.system-message-buffer-size`
    if (!wasLeader && leader) {
      for (Set<ActorRef> refs : services.values()) {
        for (ActorRef ref : refs) {
          context().watch(ref);
        }
      }
    } else if (wasLeader && !leader) {
      for (Set<ActorRef> refs : services.values()) {
        for (ActorRef ref : refs) {
          context().unwatch(ref);
        }
      }
    }
  }

  private void receiveTerminated(ActorRef ref) {
    for (Map.Entry<String, Set<ActorRef>> entry : services.entrySet()) {
      if (entry.getValue().contains(ref)) {
        log.debug("Service with name [{}] terminated: {}", entry.getKey(), ref);
        ServiceKey dKey = serviceKey(entry.getKey());
        Update<ORSet<ActorRef>> update = new Update<>(dKey, ORSet.create(), Replicator.writeLocal(),
            curr -> curr.remove(node, ref));
        replicator.tell(update, self());
      }
    }
  }

  private PartialFunction<Object, BoxedUnit> matchOther() {
    return ReceiveBuilder
      .match(UpdateResponse.class, u -> {
        // ok
      })
      .build();
  }



}