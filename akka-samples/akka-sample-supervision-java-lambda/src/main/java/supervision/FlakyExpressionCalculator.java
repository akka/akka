package supervision;

import akka.actor.*;
import akka.japi.pf.DeciderBuilder;
import akka.japi.pf.ReceiveBuilder;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static supervision.Expression.*;
import static supervision.FlakyExpressionCalculator.Position.*;

public class FlakyExpressionCalculator extends AbstractLoggingActor {

  public static Props props(Expression expr, Position position) {
    return Props.create(FlakyExpressionCalculator.class, expr, position);
  }

  // Encodes the original position of a sub-expression in its parent expression
  // Example: (4 / 2) has position Left in the original expression (4 / 2) * 3
  public static enum Position {
    Left, Right
  }

  public static class Result {
    private final Expression originalExpression;
    private final Integer value;
    private final Position position;

    public Result(Expression originalExpression, Integer value, Position position) {
      this.originalExpression = originalExpression;
      this.value = value;
      this.position = position;
    }

    public Expression getOriginalExpression() {
      return originalExpression;
    }

    public Integer getValue() {
      return value;
    }

    public Position getPosition() {
      return position;
    }
  }

  public static class FlakinessException extends RuntimeException {
    static final long serialVersionUID = 1;

    public FlakinessException() {
      super("Flakiness");
    }
  }

  // This actor has the sole purpose of calculating a given expression and
  // return the result to its parent. It takes an additional argument,
  // myPosition, which is used to signal the parent which side of its
  // expression has been calculated.
  private final Expression expr;
  private final Position myPosition;

  private Expression getExpr() {
    return expr;
  }

  private SupervisorStrategy strategy = new OneForOneStrategy(false, DeciderBuilder.
    match(FlakinessException.class, e -> {
      log().warning("Evaluation of {} failed, restarting.", getExpr());
      return SupervisorStrategy.restart();
    }).
    matchAny(e -> SupervisorStrategy.escalate()).build());
  @Override
  public SupervisorStrategy supervisorStrategy() {
    return strategy;
  }

  // The value of these variables will be reinitialized after every restart.
  // The only stable data the actor has during restarts is those embedded in
  // the Props when it was created. In this case expr, and myPosition.
  Map<Position, Integer> results  = new HashMap<>();
  Set<Position> expected = Stream.of(Left, Right).collect(Collectors.toSet());

  @Override
  public void preStart() {
    if (expr instanceof Const) {
      Integer value = ((Const) expr).getValue();
      context().parent().tell(new Result(expr, value, myPosition), self());
      // Don't forget to stop the actor after it has nothing more to do
      context().stop(self());
    }
    else {
      context().actorOf(FlakyExpressionCalculator.props(expr.getLeft(), Left), "left");
      context().actorOf(FlakyExpressionCalculator.props(expr.getRight(), Right), "right");
    }
  }

  public FlakyExpressionCalculator(Expression expr, Position myPosition) {
    this.expr = expr;
    this.myPosition = myPosition;

    receive(ReceiveBuilder.
      match(Result.class, r -> expected.contains(r.getPosition()), r -> {
        expected.remove(r.getPosition());
        results.put(r.getPosition(), r.getValue());
        if (results.size() == 2) {
          // Sometimes we fail to calculate
          flakiness();
          Integer result = evaluate(expr, results.get(Left),results.get(Right));
          log().info("Evaluated expression {} to value {}", expr, result);
          context().parent().tell(new Result(expr, result, myPosition), self());
          // Don't forget to stop the actor after it has nothing more to do
          context().stop(self());
        }
      }).match(Result.class, r -> {
      throw new IllegalStateException("Expected results for positions " +
        expected.stream().map(Object::toString).collect(Collectors.joining(", ")) +
        " but got position " + r.getPosition());
    }).build());
  }

  private Integer evaluate(Expression expr, Integer left, Integer right) {
    if (expr instanceof Add) {
      return left + right;
    } else if( expr instanceof Multiply) {
      return left * right;
    } else if (expr instanceof Divide) {
      return left / right;
    } else {
      throw new IllegalStateException("Unknown expression type " + expr.getClass());
    }
  }

  private void flakiness() throws FlakinessException {
    if (ThreadLocalRandom.current().nextDouble() < 0.2)
      throw new FlakinessException();
  }
}
