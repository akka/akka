package akka.pattern;

import akka.util.Unsafe;

/**
 * akka.pattern
 * Date: 5/20/12
 * Time: 1:34 PM
 */
public class AbstractCircuitBreaker {
    protected final static long stateOffset;

    static {
        try {
            stateOffset = Unsafe.instance.objectFieldOffset(CircuitBreaker.class.getDeclaredField("_currentStateDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }
}
