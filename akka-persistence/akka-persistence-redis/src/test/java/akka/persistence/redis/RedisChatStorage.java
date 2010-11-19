package akka.persistence.redis;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import akka.transactor.UntypedTransactor;
import akka.persistence.common.PersistentVector;
import akka.persistence.redis.RedisStorage;
import akka.stm.*;

public class RedisChatStorage extends UntypedTransactor {
    private final String CHAT_LOG = "akka.chat.log";
	private PersistentVector<byte[]> chatLog = null;

	public RedisChatStorage() {
	    chatLog = RedisStorage.newVector(CHAT_LOG);
	}

	public void atomically(final Object msg) throws Exception {
	    if (msg instanceof ChatMessage) {
		    new Atomic() {
			    public Object atomically() {
				    try {
					    return chatLog.add(((ChatMessage) msg).getMessage().getBytes("UTF-8"));
					} catch (UnsupportedEncodingException e) {
					    e.printStackTrace();
					}
					return null;
				}
			}.execute();
		} else if (msg instanceof GetChatLog) {
			List<String> messageList = new Atomic<List<String>>() {
				public List<String> atomically() {
					List<String> messages = new ArrayList<String>();

					for (byte[] messageBytes : chatLog.asJavaList())
						try {
							messages.add(new String(messageBytes, "UTF-8"));
						} catch (UnsupportedEncodingException e) {
							e.printStackTrace();
						}
					return messages;
				}
			}.execute();
			getContext().replyUnsafe(new ChatLog(messageList));
		}
	}

	public void postRestart(Throwable reason) {
		chatLog = RedisStorage.getVector(CHAT_LOG);
	}
}
