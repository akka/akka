package sample.distributeddata;

import static java.util.concurrent.TimeUnit.SECONDS;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import scala.PartialFunction;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.cluster.ddata.DistributedData;
import akka.cluster.ddata.Key;
import akka.cluster.ddata.LWWMap;
import akka.cluster.ddata.LWWMapKey;
import akka.cluster.ddata.Replicator;
import akka.cluster.ddata.Replicator.GetFailure;
import akka.cluster.ddata.Replicator.GetResponse;
import akka.cluster.ddata.Replicator.GetSuccess;
import akka.cluster.ddata.Replicator.NotFound;
import akka.cluster.ddata.Replicator.ReadConsistency;
import akka.cluster.ddata.Replicator.ReadMajority;
import akka.cluster.ddata.Replicator.Update;
import akka.cluster.ddata.Replicator.UpdateFailure;
import akka.cluster.ddata.Replicator.UpdateSuccess;
import akka.cluster.ddata.Replicator.UpdateTimeout;
import akka.cluster.ddata.Replicator.WriteConsistency;
import akka.cluster.ddata.Replicator.WriteMajority;
import akka.japi.pf.ReceiveBuilder;

@SuppressWarnings("unchecked")
public class ShoppingCart extends AbstractActor {

  //#read-write-majority
  private final WriteConsistency writeMajority = 
      new WriteMajority(Duration.create(3, SECONDS));
  private final static ReadConsistency readMajority = 
      new ReadMajority(Duration.create(3, SECONDS));
  //#read-write-majority

  public static final String GET_CART = "getCart";

  public static class AddItem {
    public final LineItem item;

    public AddItem(LineItem item) {
      this.item = item;
    }
  }

  public static class RemoveItem {
    public final String productId;

    public RemoveItem(String productId) {
      this.productId = productId;
    }
  }

  public static class Cart {
    public final Set<LineItem> items;

    public Cart(Set<LineItem> items) {
      this.items = items;
    }
  }

  public static class LineItem implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String productId;
    public final String title;
    public final int quantity;

    public LineItem(String productId, String title, int quantity) {
      this.productId = productId;
      this.title = title;
      this.quantity = quantity;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((productId == null) ? 0 : productId.hashCode());
      result = prime * result + quantity;
      result = prime * result + ((title == null) ? 0 : title.hashCode());
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
      LineItem other = (LineItem) obj;
      if (productId == null) {
        if (other.productId != null)
          return false;
      } else if (!productId.equals(other.productId))
        return false;
      if (quantity != other.quantity)
        return false;
      if (title == null) {
        if (other.title != null)
          return false;
      } else if (!title.equals(other.title))
        return false;
      return true;
    }

    @Override
    public String toString() {
      return "LineItem [productId=" + productId + ", title=" + title + ", quantity=" + quantity + "]";
    }

  }

  public static Props props(String userId) {
    return Props.create(ShoppingCart.class, userId);
  }

  private final ActorRef replicator = DistributedData.get(context().system()).replicator();
  private final Cluster node = Cluster.get(context().system());

  @SuppressWarnings("unused")
  private final String userId;
  private final Key<LWWMap<LineItem>> dataKey;

  public ShoppingCart(String userId) {
    this.userId = userId;
    this.dataKey = LWWMapKey.create("cart-" + userId);

    receive(matchGetCart()
        .orElse(matchAddItem())
        .orElse(matchRemoveItem())
        .orElse(matchOther()));
  }


  //#get-cart
  private PartialFunction<Object, BoxedUnit> matchGetCart() {
    return ReceiveBuilder
      .matchEquals((GET_CART),
          s -> receiveGetCart())
      .match(GetSuccess.class, g -> isResponseToGetCart(g),
          g -> receiveGetSuccess((GetSuccess<LWWMap<LineItem>>) g))
        .match(NotFound.class, n -> isResponseToGetCart(n),
          n -> receiveNotFound((NotFound<LWWMap<LineItem>>) n))
        .match(GetFailure.class, f -> isResponseToGetCart(f),
          f -> receiveGetFailure((GetFailure<LWWMap<LineItem>>) f))
      .build();
  }


  private void receiveGetCart() {
    Optional<Object> ctx = Optional.of(sender());
    replicator.tell(new Replicator.Get<LWWMap<LineItem>>(dataKey, readMajority, ctx), 
        self());
  }

  private boolean isResponseToGetCart(GetResponse<?> response) {
    return response.key().equals(dataKey) && 
        (response.getRequest().orElse(null) instanceof ActorRef);
  }

  private void receiveGetSuccess(GetSuccess<LWWMap<LineItem>> g) {
    Set<LineItem> items = new HashSet<>(g.dataValue().getEntries().values());
    ActorRef replyTo = (ActorRef) g.getRequest().get();
    replyTo.tell(new Cart(items), self());
  }

  private void receiveNotFound(NotFound<LWWMap<LineItem>> n) {
    ActorRef replyTo = (ActorRef) n.getRequest().get();
    replyTo.tell(new Cart(new HashSet<>()), self());
  }

  private void receiveGetFailure(GetFailure<LWWMap<LineItem>> f) {
    // ReadMajority failure, try again with local read
    Optional<Object> ctx = Optional.of(sender());
    replicator.tell(new Replicator.Get<LWWMap<LineItem>>(dataKey, Replicator.readLocal(), 
        ctx), self());
  }
  //#get-cart

  //#add-item
  private PartialFunction<Object, BoxedUnit> matchAddItem() {
    return ReceiveBuilder
      .match(AddItem.class, r -> receiveAddItem(r))
      .build();
  }

  private void receiveAddItem(AddItem add) {
    Update<LWWMap<LineItem>> update = new Update<>(dataKey, LWWMap.create(), writeMajority,
        cart -> updateCart(cart, add.item));
    replicator.tell(update, self());
  }

  //#add-item

  private LWWMap<LineItem> updateCart(LWWMap<LineItem> data, LineItem item) {
    if (data.contains(item.productId)) {
      LineItem existingItem = data.get(item.productId).get();
      int newQuantity = existingItem.quantity + item.quantity;
      LineItem newItem = new LineItem(item.productId, item.title, newQuantity);
      return data.put(node, item.productId, newItem);
    } else {
      return data.put(node, item.productId, item);
    }
  }

  private PartialFunction<Object, BoxedUnit> matchRemoveItem() {
    return ReceiveBuilder
      .match(RemoveItem.class, r -> receiveRemoveItem(r))
      .match(GetSuccess.class, g -> isResponseToRemoveItem(g),
          g -> receiveRemoveItemGetSuccess((GetSuccess<LWWMap<LineItem>>) g))
      .match(GetFailure.class, f -> isResponseToRemoveItem(f),
          f -> receiveRemoveItemGetFailure((GetFailure<LWWMap<LineItem>>) f))
      .match(NotFound.class, n -> isResponseToRemoveItem(n), n -> {/* nothing to remove */})
      .build();
  }

  //#remove-item
  private void receiveRemoveItem(RemoveItem rm) {
    // Try to fetch latest from a majority of nodes first, since ORMap
    // remove must have seen the item to be able to remove it.
    Optional<Object> ctx = Optional.of(rm);
    replicator.tell(new Replicator.Get<LWWMap<LineItem>>(dataKey, readMajority, ctx), 
        self());
  }

  private void receiveRemoveItemGetSuccess(GetSuccess<LWWMap<LineItem>> g) {
    RemoveItem rm = (RemoveItem) g.getRequest().get();
    removeItem(rm.productId);
  }


  private void receiveRemoveItemGetFailure(GetFailure<LWWMap<LineItem>> f) {
    // ReadMajority failed, fall back to best effort local value
    RemoveItem rm = (RemoveItem) f.getRequest().get();
    removeItem(rm.productId);
  }

  private void removeItem(String productId) {
    Update<LWWMap<LineItem>> update = new Update<>(dataKey, LWWMap.create(), writeMajority,
        cart -> cart.remove(node, productId));
    replicator.tell(update, self());
  }

  private boolean isResponseToRemoveItem(GetResponse<?> response) {
    return response.key().equals(dataKey) && 
        (response.getRequest().orElse(null) instanceof RemoveItem);
  }
  //#remove-item

  private PartialFunction<Object, BoxedUnit> matchOther() {
    return ReceiveBuilder
      .match(UpdateSuccess.class, u -> {
        // ok
      })
      .match(UpdateTimeout.class, t -> {
        // will eventually be replicated
      })
      .match(UpdateFailure.class, f -> {
        throw new IllegalStateException("Unexpected failure: " + f);
      })
      .build();
  }



}