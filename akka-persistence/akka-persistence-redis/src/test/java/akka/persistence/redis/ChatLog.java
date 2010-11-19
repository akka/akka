package akka.persistence.redis;

import java.util.List;

public class ChatLog extends Event {

	private static final long serialVersionUID = -7318212379604445117L;
	private List<String> log = null;

	public ChatLog(List<String> log) {
		this.log = log;
	}

	public List<String> getLog() {
		return log;
	}

	public String getLogString(String separator) {
		String result = "";
		for (String logEntry : log)
			result = result + separator + logEntry;
		return result;
	}
}
