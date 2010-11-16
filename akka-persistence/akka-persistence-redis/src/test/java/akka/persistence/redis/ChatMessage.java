package akka.persistence.redis;

public class ChatMessage extends Event {

	private static final long serialVersionUID = -764895205230020563L;
	private String from = null;
	private String message = null;

	public ChatMessage(String from, String message) {
		this.from = from;
		this.message = message;
	}

	public String getFrom() {
		return from;
	}

	public String getMessage() {
		return message;
	}
}
