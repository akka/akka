/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.extension;

//#imports
import akka.actor.*;
import java.util.concurrent.atomic.AtomicLong;

//#imports

import org.junit.Test;

public class ExtensionDocTestBase {

  //#extension
  public static class CountExtensionImpl implements Extension {
    //Since this Extension is a shared instance
    // per ActorSystem we need to be threadsafe
    private final AtomicLong counter = new AtomicLong(0);

    //This is the operation this Extension provides
    public long increment() {
      return counter.incrementAndGet();
    }
  }

  //#extension

  //#extensionid
  public static class CountExtension extends AbstractExtensionId<CountExtensionImpl> implements ExtensionIdProvider {
    //This will be the identifier of our CountExtension
    public final static CountExtension instance = new CountExtension();

    //The lookup method is required by ExtensionIdProvider,
    // so we return ourselves here, this allows us
    // to configure our extension to be loaded when
    // the ActorSystem starts up
    public CountExtension lookup() {
      return CountExtension.instance; //The public static final
    }

    //This method will be called by Akka
    // to instantiate our Extension
    public CountExtensionImpl createExtension(ActorSystemImpl system) {
      return new CountExtensionImpl();
    }
  }

  //#extensionid

  //#extension-usage-actor
  public static class MyActor extends UntypedActor {
    public void onReceive(Object msg) {
      CountExtension.instance.get(getContext().system()).increment();
    }
  }

  //#extension-usage-actor

  @Test
  public void demonstrateHowToCreateAndUseAnAkkaExtensionInJava() {
    final ActorSystem system = null;
    try {
      //#extension-usage
      CountExtension.instance.get(system).increment();
      //#extension-usage
    } catch (Exception e) {
      //do nothing
    }
  }

}
