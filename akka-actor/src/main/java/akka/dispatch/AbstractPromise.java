/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch;

import akka.util.Unsafe;

abstract class AbstractPromise {
    private volatile Object _ref;

    {
    	if (!updateState(null, DefaultPromise.EmptyPending()))
    		throw new ExceptionInInitializerError(new IllegalStateException("AbstractPromise initial value not null!"));
    }

    final static long _refOffset;

    static {
        try {
          _refOffset = Unsafe.instance.objectFieldOffset(AbstractPromise.class.getDeclaredField("_ref"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }

  protected final boolean updateState(Object oldState, Object newState) { 
  	return Unsafe.instance.compareAndSwapObject(this, _refOffset, oldState, newState);
  }

  protected final Object getState() {
  	return Unsafe.instance.getObjectVolatile(this, _refOffset);
  }
}
