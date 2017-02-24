package docs.cluster;

import java.math.BigInteger;
import scala.concurrent.Future;
import akka.actor.AbstractActor;
import akka.dispatch.Mapper;
import static akka.dispatch.Futures.future;
import static akka.pattern.Patterns.pipe;

//#backend
public class FactorialBackend extends AbstractActor {

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(Integer.class, n -> {
        Future<BigInteger> f = future(() -> factorial(n),
          getContext().dispatcher());

        Future<FactorialResult> result = f.map(
          new Mapper<BigInteger, FactorialResult>() {
            public FactorialResult apply(BigInteger factorial) {
              return new FactorialResult(n, factorial);
            }
          }, getContext().dispatcher());

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

