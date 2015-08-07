package akka.typed.javadsl.test;

import org.junit.Test;
import static org.junit.Assert.*;

import akka.typed.ActorSystem;
import akka.typed.Inbox;
import akka.typed.Props;
import akka.typed.javadsl.Counter;
import akka.typed.javadsl.State;

public class CounterTest {

	@Test
	public void testAPI() {
		final ActorSystem<State<Counter>> sys = ActorSystem.create("first",
				Props.create(Counter::initial));
		try {
			final Inbox<Long> replyTo = new Inbox<>("int");
			sys.tell(s -> s.get(replyTo.ref()));
			assertEquals((Long) 0L, replyTo.awaitMsg(1000));
			sys.tell(Counter::incr);
			sys.tell(s -> s.get(replyTo.ref()));
			assertEquals((Long) 1L, replyTo.awaitMsg(1000));
		} finally {
			sys.terminate();
		}
	}

}
