package sample.distributeddata;

import static akka.cluster.ddata.Replicator.readLocal;
import static akka.cluster.ddata.Replicator.writeLocal;

import java.util.Optional;
import scala.Option;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.cluster.ddata.DistributedData;
import akka.cluster.ddata.Key;
import akka.cluster.ddata.LWWMap;
import akka.cluster.ddata.LWWMapKey;
import akka.cluster.ddata.Replicator.Get;
import akka.cluster.ddata.Replicator.GetSuccess;
import akka.cluster.ddata.Replicator.NotFound;
import akka.cluster.ddata.Replicator.Update;
import akka.cluster.ddata.Replicator.UpdateResponse;
import akka.japi.pf.ReceiveBuilder;

@SuppressWarnings("unchecked")
public class ReplicatedCache extends AbstractActor {

  static class Request {
    public final String key;
    public final ActorRef replyTo;

    public Request(String key, ActorRef replyTo) {
      this.key = key;
      this.replyTo = replyTo;
    }
  }

  public static class PutInCache {
    public final String key;
    public final Object value;

    public PutInCache(String key, Object value) {
      this.key = key;
      this.value = value;
    }
  }

  public static class GetFromCache {
    public final String key;

    public GetFromCache(String key) {
      this.key = key;
    }
  }

  public static class Cached {
    public final String key;
    public final Optional<Object> value;

    public Cached(String key, Optional<Object> value) {
      this.key = key;
      this.value = value;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((key == null) ? 0 : key.hashCode());
      result = prime * result + ((value == null) ? 0 : value.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      Cached other = (Cached) obj;
      if (key == null) {
        if (other.key != null)
          return false;
      } else if (!key.equals(other.key))
        return false;
      if (value == null) {
        if (other.value != null)
          return false;
      } else if (!value.equals(other.value))
        return false;
      return true;
    }

    @Override
    public String toString() {
      return "Cached [key=" + key + ", value=" + value + "]";
    }

  }

  public static class Evict {
    public final String key;

    public Evict(String key) {
      this.key = key;
    }
  }

  public static Props props() {
    return Props.create(ReplicatedCache.class);
  }

  private final ActorRef replicator = DistributedData.get(context().system()).replicator();
  private final Cluster node = Cluster.get(context().system());

  public ReplicatedCache() {
    receive(ReceiveBuilder
      .match(PutInCache.class, cmd -> receivePutInCache(cmd.key, cmd.value))
      .match(Evict.class, cmd -> receiveEvict(cmd.key))
      .match(GetFromCache.class, cmd -> receiveGetFromCache(cmd.key))
      .match(GetSuccess.class, g -> receiveGetSuccess((GetSuccess<LWWMap<Object>>) g))
      .match(NotFound.class, n -> receiveNotFound((NotFound<LWWMap<Object>>) n))
      .match(UpdateResponse.class, u -> {})
      .build());
  }

  private void receivePutInCache(String key, Object value) {
    Update<LWWMap<Object>> update = new Update<>(dataKey(key), LWWMap.create(), writeLocal(),
        curr -> curr.put(node, key, value));
    replicator.tell(update, self());
  }

  private void receiveEvict(String key) {
    Update<LWWMap<Object>> update = new Update<>(dataKey(key), LWWMap.create(), writeLocal(),
        curr -> curr.remove(node, key));
    replicator.tell(update, self());
  }

  private void receiveGetFromCache(String key) {
    Optional<Object> ctx = Optional.of(new Request(key, sender()));
    Get<LWWMap<Object>> get = new Get<>(dataKey(key), readLocal(), ctx);
    replicator.tell(get, self());
  }

  private void receiveGetSuccess(GetSuccess<LWWMap<Object>> g) {
    Request req = (Request) g.getRequest().get();
    Option<Object> valueOption = g.dataValue().get(req.key);
    Optional<Object> valueOptional = Optional.ofNullable(valueOption.isDefined() ? valueOption.get() : null);
    req.replyTo.tell(new Cached(req.key, valueOptional), self());
  }

  private void receiveNotFound(NotFound<LWWMap<Object>> n) {
    Request req = (Request) n.getRequest().get();
    req.replyTo.tell(new Cached(req.key, Optional.empty()), self());
  }

  private Key<LWWMap<Object>> dataKey(String entryKey) {
    return LWWMapKey.create("cache-" + Math.abs(entryKey.hashCode()) % 100);
  }


}