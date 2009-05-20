package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.transactional;

public interface InMemStateful {
  // transactional
  @transactional
  public void success(String key, String msg);

  @transactional
  public void failure(String key, String msg, InMemFailer failer);

  //@transactional
  //public void clashOk(String key, String msg, InMemClasher clasher);

  //@transactional
  //public void clashNotOk(String key, String msg, InMemClasher clasher);

  // non-transactional
  public String getState(String key);

  public void setState(String key, String value);
}
