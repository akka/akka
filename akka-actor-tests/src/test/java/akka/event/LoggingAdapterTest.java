package akka.event;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.JavaTestKit;
import akka.event.Logging.Error;
import akka.event.ActorWithMDC.Log;
import static akka.event.Logging.*;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.Duration;
import scala.util.control.NoStackTrace;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;


public class LoggingAdapterTest extends JUnitSuite {

    private static final Config config = ConfigFactory.parseString(
            "akka.loglevel = DEBUG\n" +
            "akka.actor.serialize-messages = off"
    );

    @Test
    public void mustFormatMessage() {
        final ActorSystem system = ActorSystem.create("test-system", config);
        final LoggingAdapter log = Logging.getLogger(system, this);
        new LogJavaTestKit(system) {{
            system.eventStream().subscribe(getRef(), LogEvent.class);
            log.error("One arg message: {}", "the arg");
            expectLog(ErrorLevel(), "One arg message: the arg");

            Throwable cause = new IllegalStateException("This state is illegal") {
                @Override
                public synchronized Throwable fillInStackTrace() {
                    return this; // no stack trace
                }
            };
            log.error(cause, "Two args message: {}, {}", "an arg", "another arg");
            expectLog(ErrorLevel(), "Two args message: an arg, another arg", cause);

            int[] primitiveArgs = {10, 20, 30};
            log.warning("Args as array of primitives: {}, {}, {}", primitiveArgs);
            expectLog(WarningLevel(), "Args as array of primitives: 10, 20, 30");

            Date now = new Date();
            UUID uuid = UUID.randomUUID();
            Object[] objArgs = {"A String", now, uuid};
            log.info("Args as array of objects: {}, {}, {}", objArgs);
            expectLog(InfoLevel(), "Args as array of objects: A String, " + now.toString() + ", " + uuid.toString());

            log.debug("Four args message: {}, {}, {}, {}", 1, 2, 3, 4);
            expectLog(DebugLevel(), "Four args message: 1, 2, 3, 4");

        }};
    }

    @Test
    public void mustCallMdcForEveryLog() throws Exception {
        final ActorSystem system = ActorSystem.create("test-system", config);
        new LogJavaTestKit(system) {{
            system.eventStream().subscribe(getRef(), LogEvent.class);
            ActorRef ref = system.actorOf(Props.create(ActorWithMDC.class));

            ref.tell(new Log(ErrorLevel(), "An Error"), system.deadLetters());
            expectLog(ErrorLevel(), "An Error", "{messageLength=8}");
            ref.tell(new Log(WarningLevel(), "A Warning"), system.deadLetters());
            expectLog(WarningLevel(), "A Warning", "{messageLength=9}");
            ref.tell(new Log(InfoLevel(), "Some Info"), system.deadLetters());
            expectLog(InfoLevel(), "Some Info", "{messageLength=9}");
            ref.tell(new Log(DebugLevel(), "No MDC for 4th call"), system.deadLetters());
            expectLog(DebugLevel(), "No MDC for 4th call");
            ref.tell(new Log(Logging.DebugLevel(), "And now yes, a debug with MDC"), system.deadLetters());
            expectLog(DebugLevel(), "And now yes, a debug with MDC", "{messageLength=29}");
        }};
    }

    @Test
    public void mustSupportMdcNull() throws Exception {
        final ActorSystem system = ActorSystem.create("test-system", config);
        new LogJavaTestKit(system) {{
            system.eventStream().subscribe(getRef(), LogEvent.class);
            ActorRef ref = system.actorOf(Props.create(ActorWithMDC.class));

            ref.tell(new Log(InfoLevel(), "Null MDC"), system.deadLetters());
            expectLog(InfoLevel(), "Null MDC", "{}");
        }};
    }

    /*
     * #3671: Let the application specify custom MDC values
     * Java backward compatibility check
     */
    @Test
    public void mustBeAbleToCreateLogEventsWithOldConstructor() throws Exception {
        assertNotNull(new Error(new Exception(), "logSource", this.getClass(), "The message"));
        assertNotNull(new Error("logSource", this.getClass(), "The message"));
        assertNotNull(new Warning("logSource", this.getClass(), "The message"));
        assertNotNull(new Info("logSource", this.getClass(), "The message"));
        assertNotNull(new Debug("logSource", this.getClass(), "The message"));
    }

    private static class LogJavaTestKit extends JavaTestKit {

        private static final String emptyMDC = "{}";

        public LogJavaTestKit(ActorSystem system) {
            super(system);
        }

        void expectLog(Object level, String message) {
            expectLog(level, message, null, emptyMDC);
        }

        void expectLog(Object level, String message, Throwable cause) {
            expectLog(level, message, cause, emptyMDC);
        }

        void expectLog(Object level, String message, String mdc) {
            expectLog(level, message, null, mdc);
        }

        void expectLog(final Object level, final String message, final Throwable cause, final String mdc) {
            new ExpectMsg<Void>(Duration.create(3, TimeUnit.SECONDS), "LogEvent") {
                protected Void match(Object event) {
                    LogEvent log = (LogEvent) event;
                    assertEquals(message, log.message());
                    assertEquals(level, log.level());
                    assertEquals(mdc, log.getMDC().toString());
                    if(cause != null) {
                        Error error = (Error) log;
                        assertSame(cause, error.cause());
                    }
                    return null;
                }
            };
        }
    }
}

