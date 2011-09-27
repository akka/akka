package akka.cluster;

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.apache.bookkeeper.proto.BookieServer;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;

public class LocalBookKeeper {
    public static final int CONNECTION_TIMEOUT = 30000;

    int numberOfBookies;

    public LocalBookKeeper() {
        numberOfBookies = 3;
    }

    public LocalBookKeeper(int numberOfBookies) {
        this();
        this.numberOfBookies = numberOfBookies;
    }

    private final String HOSTPORT = "127.0.0.1:2181";
    NIOServerCnxnFactory serverFactory;
    ZooKeeperServer zks;
    ZooKeeper zkc;
    int ZooKeeperDefaultPort = 2181;
    File ZkTmpDir;

    //BookKeeper variables
    File tmpDirs[];
    BookieServer bs[];
    Integer initialPort = 5000;

    /**
     * @param args
     */

    public void runZookeeper(int maxCC) throws IOException{
        // create a ZooKeeper server(dataDir, dataLogDir, port)
        //ServerStats.registerAsConcrete();
        //ClientBase.setupTestEnv();
        ZkTmpDir = File.createTempFile("zookeeper", "test");
        ZkTmpDir.delete();
        ZkTmpDir.mkdir();

        try {
            zks = new ZooKeeperServer(ZkTmpDir, ZkTmpDir, ZooKeeperDefaultPort);
            serverFactory =  new NIOServerCnxnFactory();
            serverFactory.configure(new InetSocketAddress(ZooKeeperDefaultPort), maxCC);
            serverFactory.startup(zks);
        } catch (Exception e) {
            // TODO Auto-generated catch block
        }

        boolean b = waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT);
    }

    public void initializeZookeper() {
        //initialize the zk client with values
        try {
            zkc = new ZooKeeper("127.0.0.1", ZooKeeperDefaultPort, new emptyWatcher());
            zkc.create("/ledgers", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zkc.create("/ledgers/available", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            // No need to create an entry for each requested bookie anymore as the
            // BookieServers will register themselves with ZooKeeper on startup.
        } catch (KeeperException e) {
        } catch (InterruptedException e) {
        } catch (IOException e) {
        }
    }

    public void runBookies() throws IOException{
        // Create Bookie Servers (B1, B2, B3)

        tmpDirs = new File[numberOfBookies];
        bs = new BookieServer[numberOfBookies];

        for(int i = 0; i < numberOfBookies; i++) {
            tmpDirs[i] = File.createTempFile("bookie" + Integer.toString(i), "test");
            tmpDirs[i].delete();
            tmpDirs[i].mkdir();

            bs[i] = new BookieServer(initialPort + i, InetAddress.getLocalHost().getHostAddress() + ":"
                    + ZooKeeperDefaultPort, tmpDirs[i], new File[]{tmpDirs[i]});
            bs[i].start();
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if(args.length < 1) {
            usage();
            System.exit(-1);
        }
        LocalBookKeeper lb = new LocalBookKeeper(Integer.parseInt(args[0]));
        lb.runZookeeper(1000);
        lb.initializeZookeper();
        lb.runBookies();
        while (true) {
            Thread.sleep(5000);
        }
    }

    private static void usage() {
        System.err.println("Usage: LocalBookKeeper number-of-bookies");
    }

    /*  User for testing purposes, void */
    class emptyWatcher implements Watcher{
        public void process(WatchedEvent event) {}
    }

    public static boolean waitForServerUp(String hp, long timeout) {
        long start = System.currentTimeMillis();
        String split[] = hp.split(":");
        String host = split[0];
        int port = Integer.parseInt(split[1]);
        while (true) {
            try {
                Socket sock = new Socket(host, port);
                BufferedReader reader = null;
                try {
                    OutputStream outstream = sock.getOutputStream();
                    outstream.write("stat".getBytes());
                    outstream.flush();

                    reader =
                        new BufferedReader(
                                new InputStreamReader(sock.getInputStream()));
                    String line = reader.readLine();
                    if (line != null && line.startsWith("Zookeeper version:")) {
                        return true;
                    }
                } finally {
                    sock.close();
                    if (reader != null) {
                        reader.close();
                    }
                }
            } catch (IOException e) {
                // ignore as this is expected
            }

            if (System.currentTimeMillis() > start + timeout) {
                break;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        return false;
    }

}
