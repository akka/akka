/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.extension;

//#imports
import akka.actor.*;
import java.util.concurrent.atomic.AtomicLong;

//#imports

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExtensionDocTestBase {

    //#extension
    public static class CountExtensionImpl implements Extension {
      //Since this Extension is a shared instance
      // per ActorSystem we need to be threadsafe
      private final AtomicLong counter = new AtomicLong(0);

      //This is the operation this Extension provides
      public long increment()  {
          return counter.incrementAndGet();
      }
    }
    //#extension

    //#extensionid
    static class CountExtensionId extends AbstractExtensionId<CountExtensionImpl> {
        //This method will be called by Akka
        // to instantiate our Extension
        public CountExtensionImpl createExtension(ActorSystemImpl i) {
          return new CountExtensionImpl();
        }
    }

    //This will be the identifier of our CountExtension
    public final static CountExtensionId CountExtension = new CountExtensionId();
    //#extensionid

    //#extensionid-provider
    static class CountExtensionIdProvider implements ExtensionIdProvider {
        public CountExtensionId lookup() {
          return CountExtension; //The public static final
        }
      }
    //#extensionid-provider

    //#extension-usage-actor
    static class MyActor extends UntypedActor {
      public void onReceive(Object msg) {
        CountExtension.get(getContext().system()).increment();
      }
    }
    //#extension-usage-actor


  @Test public void demonstrateHowToCreateAndUseAnAkkaExtensionInJava() {
    final ActorSystem system = null;
    try {
      //#extension-usage
      CountExtension.get(system).increment();
      //#extension-usage
    } catch(Exception e) {
      //do nothing
    }
  }

}
