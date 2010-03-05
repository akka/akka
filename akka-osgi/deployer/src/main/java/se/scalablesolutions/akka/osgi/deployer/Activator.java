/*
 * Copyright (C) 2010 Roman Roelofsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.scalablesolutions.akka.osgi.deployer;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

	private static final String DIRINSTALLER_POLL = "dirinstaller.poll";
	private static final String DIRINSTALLER_DIR = "dirinstaller.dir";
	
	private DirWatcher watcher;
	
	public void start(BundleContext context) throws Exception {
		String bundlesDir = context.getProperty(DIRINSTALLER_DIR);
		bundlesDir = bundlesDir == null ? "akka" : bundlesDir;
		
		String intervalStr = context.getProperty(DIRINSTALLER_POLL);
		Integer interval = intervalStr == null ? 2000 : Integer.valueOf(intervalStr);
		
		watcher = new DirWatcher(context, bundlesDir, interval);
		watcher.startWatching();
	}

	public void stop(BundleContext context) throws Exception {
		watcher.stopWatching();
	}

}
