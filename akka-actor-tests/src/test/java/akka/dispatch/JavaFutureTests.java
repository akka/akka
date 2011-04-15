package akka.dispatch;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.Callable;
import akka.japi.Function;
import akka.japi.Procedure;
import scala.Some;
import scala.Right;
import static akka.dispatch.Futures.future;

@SuppressWarnings("unchecked") public class JavaFutureTests {

    @Test public void mustBeAbleToMapAFuture() {
        Future f1 = future(new Callable<String>() {
                public String call() {
                    return "Hello";
                }
            });

        Future f2 = f1.map(new Function<String, String>() {
                public String apply(String s) {
                    return s + " World";
                }
            });

        assertEquals(new Some(new Right("Hello World")), f2.await().value());
    }

}
