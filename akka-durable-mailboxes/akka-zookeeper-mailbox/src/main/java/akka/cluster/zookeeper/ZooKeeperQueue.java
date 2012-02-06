/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.zookeeper;

import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;

import org.I0Itec.zkclient.ExceptionUtil;
import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.exception.ZkNoNodeException;

public class ZooKeeperQueue<T extends Object> {

  protected static class Element<T> {
    private String _name;
    private T _data;

    public Element(String name, T data) {
      _name = name;
      _data = data;
    }

    public String getName() {
      return _name;
    }

    public T getData() {
      return _data;
    }
  }

  protected final ZkClient _zkClient;
  private final String _elementsPath;
  private final String _rootPath;
  private final boolean _isBlocking;

  public ZooKeeperQueue(ZkClient zkClient, String rootPath, boolean isBlocking) {
    _zkClient = zkClient;
    _rootPath = rootPath;
    _isBlocking = isBlocking;
    _elementsPath = rootPath + "/queue";
    if (!_zkClient.exists(rootPath))      {
      _zkClient.createPersistent(rootPath, true);
      _zkClient.createPersistent(_elementsPath, true);
    }
  }

  public String enqueue(T element) {
    try {
      String sequential = _zkClient.createPersistentSequential(getElementRoughPath(), element);
      String elementId = sequential.substring(sequential.lastIndexOf('/') + 1);
      return elementId;
    } catch (Exception e) {
      throw ExceptionUtil.convertToRuntimeException(e);
    }
  }

  public T dequeue() throws InterruptedException {
    if (_isBlocking) {
      Element<T> element = getFirstElement();
      _zkClient.delete(getElementPath(element.getName()));
      return element.getData();
    } else {
      throw new UnsupportedOperationException("Non-blocking ZooKeeperQueue is not yet supported");
      /* TODO DOES NOT WORK
      try {
        String headName = getSmallestElement(_zkClient.getChildren(_elementsPath));
        String headPath = getElementPath(headName);
        return (T) _zkClient.readData(headPath);
      } catch (ZkNoNodeException e) {
        return null;
      }
      */
    }
  }

  public boolean containsElement(String elementId) {
    String zkPath = getElementPath(elementId);
    return _zkClient.exists(zkPath);
  }

  public T peek() throws InterruptedException {
    Element<T> element = getFirstElement();
    if (element == null) {
      return null;
    }
    return element.getData();
  }

  @SuppressWarnings("unchecked")
  public List<T> getElements() {
    List<String> paths =_zkClient.getChildren(_elementsPath);
    List<T> elements = new ArrayList<T>();
    for (String path: paths) {
      elements.add((T)_zkClient.readData(path));
    }
    return elements;
  }

  public int size() {
    return _zkClient.getChildren(_elementsPath).size();
  }

  public void clear() {
    _zkClient.deleteRecursive(_rootPath);
  }

  public boolean isEmpty() {
    return size() == 0;
  }

  private String getElementRoughPath() {
    return getElementPath("item" + "-");
  }

  private String getElementPath(String elementId) {
    return _elementsPath + "/" + elementId;
  }

  private String getSmallestElement(List<String> list) {
    String smallestElement = list.get(0);
    for (String element : list) {
      if (element.compareTo(smallestElement) < 0) {
        smallestElement = element;
      }
    }
    return smallestElement;
  }

  @SuppressWarnings("unchecked")
  protected Element<T> getFirstElement() throws InterruptedException {
    final Object mutex = new Object();
    IZkChildListener notifyListener = new IZkChildListener() {
      @Override
      public void handleChildChange(String parentPath, List<String> currentChilds) throws Exception {
        synchronized (mutex) {
          mutex.notify();
        }
      }
    };
    try {
      while (true) {
        List<String> elementNames;
        synchronized (mutex) {
          elementNames = _zkClient.subscribeChildChanges(_elementsPath, notifyListener);
          while (elementNames == null || elementNames.isEmpty()) {
            mutex.wait();
            elementNames = _zkClient.getChildren(_elementsPath);
          }
        }
        String elementName = getSmallestElement(elementNames);
        try {
          String elementPath = getElementPath(elementName);
          return new Element<T>(elementName, (T) _zkClient.readData(elementPath));
        } catch (ZkNoNodeException e) {
          // somebody else picked up the element first, so we have to
          // retry with the new first element
        }
      }
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      throw ExceptionUtil.convertToRuntimeException(e);
    } finally {
      _zkClient.unsubscribeChildChanges(_elementsPath, notifyListener);
    }
  }

}
