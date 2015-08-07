package akka.typed.javadsl;

import akka.typed.ActorRef;
import akka.typed.Behavior;

public interface Counter {

	public Counter incr();

	public Counter get(ActorRef<Long> replyTo);

	static class C implements Counter {
		private final long c;

		public C(long c) {
			this.c = c;
		}

		@Override
		public Counter incr() {
			return new C(c + 1);
		}

		@Override
		public Counter get(ActorRef<Long> replyTo) {
			replyTo.tell(c);
			return this;
		}
	}
	
	static final Behavior<State<Counter>> zero = new StateBehavior<>(new C(0));

	public static Behavior<State<Counter>> initial() {
		return zero;
	}

}
