package akka.actor;

public interface RemoteTypedActorOne {
  public String requestReply(String s) throws Exception;
  public void oneWay() throws Exception;
}
