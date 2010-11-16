package akka.persistence.redis;

public class GetChatLog extends Event {

	private static final long serialVersionUID = -7000786115556740575L;
	private String from = null;

	public GetChatLog(String from) {
		this.from = from;
	}

	public String getFrom() {
		return from;
	}
}
