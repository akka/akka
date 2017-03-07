package docs.cluster;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

import akka.actor.AbstractActor;
import static akka.pattern.PatternsCS.pipe;

//#backend
public class FactorialBackend extends AbstractActor {

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(Integer.class, n -> {

        CompletableFuture<FactorialResult> result =
          CompletableFuture.supplyAsync(() -> factorial(n))
            .thenApply((factorial) -> new FactorialResult(n, factorial));

        pipe(result, getContext().dispatcher()).to(sender());

      })
      .build();
  }

  BigInteger factorial(int n) {
    BigInteger acc = BigInteger.ONE;
    for (int i = 1; i <= n; ++i) {
      acc = acc.multiply(BigInteger.valueOf(i));
    }
    return acc;
  }
}
//#backend

