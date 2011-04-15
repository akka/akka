/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.config;

import java.util.List;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
//import com.google.inject.jsr250.ResourceProviderFactory;

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
public class TypedActorGuiceModule extends AbstractModule {
  private final List<DependencyBinding> bindings;

  public TypedActorGuiceModule(final List<DependencyBinding> bindings) {
    this.bindings = bindings;
  }

  protected void configure() {
    //bind(ResourceProviderFactory.class);
    for (int i = 0; i < bindings.size(); i++) {
      final DependencyBinding db = bindings.get(i);
      //if (db.getInterface() ne null) bind((Class) db.getInterface()).to((Class) db.getTarget()).in(Singleton.class);
      //else

      @SuppressWarnings("unchecked")
      Class<Object> intf = db.getInterface();
      this.bind(intf).toInstance(db.getTarget());
    }
  }
}
