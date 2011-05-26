package akka.spring;

public interface RemoteTypedActorOne {
  public String requestReply(String s) throws Exception;
  public void oneWay() throws Exception;
}
