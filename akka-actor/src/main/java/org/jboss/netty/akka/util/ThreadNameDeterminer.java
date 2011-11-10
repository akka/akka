/*
 * Copyright 2009 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.jboss.netty.akka.util;

/**
 * Overrides the thread name proposed by {@link ThreadRenamingRunnable}.
 *
 * @author <a href="http://www.jboss.org/netty/">The Netty Project</a>
 * @author <a href="http://gleamynode.net/">Trustin Lee</a>
 * @version $Rev: 2080 $, $Date: 2010-01-26 18:04:19 +0900 (Tue, 26 Jan 2010) $
 */
public interface ThreadNameDeterminer {

    /**
     * {@link org.jboss.netty.akka.util.ThreadNameDeterminer} that accepts the proposed thread name
     * as is.
     */
    ThreadNameDeterminer PROPOSED = new ThreadNameDeterminer() {
        public String determineThreadName(String currentThreadName,
                String proposedThreadName) throws Exception {
            return proposedThreadName;
        }
    };

    /**
     * {@link org.jboss.netty.akka.util.ThreadNameDeterminer} that rejects the proposed thread name and
     * retains the current one.
     */
    ThreadNameDeterminer CURRENT = new ThreadNameDeterminer() {
        public String determineThreadName(String currentThreadName,
                String proposedThreadName) throws Exception {
            return null;
        }
    };

    /**
     * Overrides the thread name proposed by {@link ThreadRenamingRunnable}.
     *
     * @param currentThreadName   the current thread name
     * @param proposedThreadName  the proposed new thread name
     * @return the actual new thread name.
     *         If {@code null} is returned, the proposed thread name is
     *         discarded (i.e. no rename).
     */
    String determineThreadName(String currentThreadName, String proposedThreadName) throws Exception;
}
