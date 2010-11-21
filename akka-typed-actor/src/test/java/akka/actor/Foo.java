package akka.actor;

public interface Foo {
  public Foo body();
  public Bar getBar();

  public String foo(String msg);
  public void bar(String msg);

  public String longRunning();
  public String throwsException();

  public int $tag() throws java.rmi.RemoteException;
}
