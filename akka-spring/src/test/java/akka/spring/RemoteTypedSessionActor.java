package akka.spring;

public interface RemoteTypedSessionActor {

  public void login(String user);
  public String getUser();
  public void doSomethingFunny() throws Exception;
}
