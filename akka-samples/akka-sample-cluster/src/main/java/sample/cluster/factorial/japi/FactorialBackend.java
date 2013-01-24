package sample.cluster.factorial.japi;

//#imports
import java.math.BigInteger;
import java.util.concurrent.Callable;
import scala.concurrent.Future;
import akka.actor.UntypedActor;
import akka.dispatch.Mapper;
import static akka.dispatch.Futures.future;
import static akka.pattern.Patterns.pipe;
//#imports

//#backend
public class FactorialBackend extends UntypedActor {

  @Override
  public void onReceive(Object message) {
    if (message instanceof Integer) {
      final Integer n = (Integer) message;
      Future<BigInteger> f = future(new Callable<BigInteger>() {
        public BigInteger call() {
          return factorial(n);
        }
      }, getContext().dispatcher());

      Future<FactorialResult> result = f.map(
        new Mapper<BigInteger, FactorialResult>() {
          public FactorialResult apply(BigInteger factorial) {
            return new FactorialResult(n, factorial);
          }
        }, getContext().dispatcher());

      pipe(result, getContext().dispatcher()).to(getSender());

    } else {
      unhandled(message);
    }
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

