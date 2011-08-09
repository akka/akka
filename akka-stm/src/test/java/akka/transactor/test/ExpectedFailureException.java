package akka.transactor.test;

public class ExpectedFailureException extends RuntimeException {
    public ExpectedFailureException() {
        super("Expected failure");
    }
}
