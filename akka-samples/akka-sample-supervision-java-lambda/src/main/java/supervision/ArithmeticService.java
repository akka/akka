package supervision;

import akka.actor.*;
import akka.japi.pf.DeciderBuilder;
import akka.japi.pf.ReceiveBuilder;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

import java.util.HashMap;
import java.util.Map;

import static  supervision.FlakyExpressionCalculator.FlakinessException;
import static  supervision.FlakyExpressionCalculator.Result;
import static  supervision.FlakyExpressionCalculator.Position.Left;

// A very simple service that accepts arithmetic expressions and tries to
// evaluate them. Since the calculation is dangerous (at least for the sake
// of this example) it is delegated to a worker actor of type
// FlakyExpressionCalculator.
class ArithmeticService extends AbstractLoggingActor {

  // Map of workers to the original actors requesting the calculation
  Map<ActorRef, ActorRef> pendingWorkers = new HashMap<>();

  private SupervisorStrategy strategy = new OneForOneStrategy(false, DeciderBuilder.
    match(FlakinessException.class, e -> {
      log().warning("Evaluation of a top level expression failed, restarting.");
      return SupervisorStrategy.restart();
    }).
    match(ArithmeticException.class, e -> {
      log().error("Evaluation failed because of: {}", e.getMessage());
      notifyConsumerFailure(sender(), e);
      return SupervisorStrategy.stop();
    }).
    match(Throwable.class, e -> {
      log().error("Unexpected failure: {}", e.getMessage());
      notifyConsumerFailure(sender(), e);
      return SupervisorStrategy.stop();
    }).build());
  @Override
  public SupervisorStrategy supervisorStrategy() {
    return strategy;
  }

  private void notifyConsumerFailure(ActorRef worker, Throwable failure) {
    // Status.Failure is a message type provided by the Akka library. The
    // reason why it is used is because it is recognized by the "ask" pattern
    // and the Future returned by ask will fail with the provided exception.
    ActorRef pending = pendingWorkers.get(worker);
    if (pending != null) {
      pending.tell(new Status.Failure(failure), self());
      pendingWorkers.remove(worker);
    }
  }

  private void notifyConsumerSuccess(ActorRef worker, Integer result) {
    ActorRef pending = pendingWorkers.get(worker);
    if (pending != null) {
      pending.tell(result, self());
      pendingWorkers.remove(worker);
    }
  }

  ArithmeticService() {
    receive(ReceiveBuilder.
      match(Expression.class, expr -> {
        // We delegate the dangerous task of calculation to a worker, passing the
        // expression as a constructor argument to the actor.
        ActorRef worker = context().actorOf(FlakyExpressionCalculator.props(expr, Left));
        pendingWorkers.put(worker, sender());
      }).
      match(Result.class, r -> notifyConsumerSuccess(sender(), r.getValue())).build()
    );
  }
}
