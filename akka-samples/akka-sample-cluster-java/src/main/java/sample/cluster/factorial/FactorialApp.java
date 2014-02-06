package sample.cluster.factorial;

public class FactorialApp {

  public static void main(String[] args) {
    // starting 3 backend nodes and 1 frontend node
    FactorialBackendMain.main(new String[] { "2551" });
    FactorialBackendMain.main(new String[] { "2552" });
    FactorialBackendMain.main(new String[0]);
    FactorialFrontendMain.main(new String[0]);
  }
}
