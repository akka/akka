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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.util.tracker.ServiceTracker;

public class DirWatcher {

	private Thread thread;

	private ServiceTracker packageAdminTracker;

	private final BundleContext context;
	private final String bundlesDir;
	private final int interval;

	private final Map<String, Long> timestamps = new HashMap<String, Long>();
	private boolean modifiedSinceLastRun = false;

	private boolean warningMissingLoadDirPresented = false;

	public DirWatcher(BundleContext context, String bundlesDir, int interval) {
		this.packageAdminTracker = new ServiceTracker(context, PackageAdmin.class.getName(), null);
		this.packageAdminTracker.open();

		this.context = context;
		this.bundlesDir = bundlesDir;
		this.interval = interval;
	}

	public void startWatching() {
		thread = new Thread() {
			@Override
			public void run() {
				try {
					while (!Thread.interrupted()) {
						modifiedSinceLastRun = false;
						List<File> found = new ArrayList<File>();
						getAllFiles(found, bundlesDir);
						analyseNewState(found);
						Thread.sleep(interval);
					}
				} catch (InterruptedException e) {
				}
			}
		};
		thread.start();
	}

	public void stopWatching() {
		thread.interrupt();
	}

	private void getAllFiles(List<File> found, String dirName) {
		File dir = new File(dirName);
		File[] files = dir.listFiles();
		if (files == null) {
			if (!warningMissingLoadDirPresented) {
				System.out.println("DirInstaller WARNING: Directory '" + dirName + "' does not exist!");
				warningMissingLoadDirPresented = true;
			}
			return;
		}

		for (File f : files) {
			try {
				if (f.isFile())
					if (f.getName().endsWith(".cfg")) {
						found.add(0, f);
					} else {
						found.add(f);
					}
				else
					getAllFiles(found, f.getCanonicalPath());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void analyseNewState(List<File> found) {
		// check for new or updated bundles
		for (File file : found) {
			try {
				String string = file.getCanonicalPath();
				Long time = timestamps.get(string);

				// time == null: system startup
				// time < lastModified: updated file
				if (time == null || time < file.lastModified()) {
					timestamps.put(string, file.lastModified());
					modifiedSinceLastRun = true;

					if (string.endsWith(".jar"))
						installOrUpdateBundle(string, time == null);
				}
			} catch (IOException e) {
				System.out.println("DirInstaller: Problems accessing file " + file.getName());
				e.printStackTrace();
			} catch (BundleException e) {
				System.out.println("DirInstaller: Problems installing or updating bundle. File: "
						+ file.getName());
				e.printStackTrace();
			}
		}

		// check removed bundles
		Iterator<String> it = timestamps.keySet().iterator();
		while (it.hasNext()) {
			String s = it.next();
			try {
				if (!containsFilename(s, found)) {
					for (Bundle b : context.getBundles()) {
						if (b.getLocation().equals("file:" + s)) {
							System.out.println("Removing bundle '" + b.getSymbolicName() + "'");
							b.uninstall();
							modifiedSinceLastRun = true;
						}
					}
					it.remove();
					timestamps.remove(s);
				}
			} catch (BundleException e) {
				System.out.println("DirInstaller: Problems uninstalling bundle: " + s);
			} catch (IOException e) {
				System.out.println("DirInstaller: Problems processing file: " + e);
			}
		}

		if (modifiedSinceLastRun)
			startAllAndRefresh();
	}

	private boolean containsFilename(String string, List<File> fileList) throws IOException {
		for (File f : fileList) {
			if (f.getCanonicalPath().equals(string))
				return true;
		}
		return false;
	}

	private void startAllAndRefresh() {
		for (Bundle b : context.getBundles()) {
			try {
				if (b.getState() != Bundle.ACTIVE && !isFragment(b)) {
					b.start();
				}
			} catch (BundleException e) {
				System.out.println("Problems starting bundle: " + b);
				e.printStackTrace();
			}
		}
		PackageAdmin admin = (PackageAdmin) this.packageAdminTracker.getService();
		System.out.println("DirInstaller: Refreshing packages");
		admin.refreshPackages(null);
	}

	private boolean isFragment(Bundle b) {
		PackageAdmin admin = (PackageAdmin) this.packageAdminTracker.getService();
		return admin.getBundleType(b) == PackageAdmin.BUNDLE_TYPE_FRAGMENT;
	}

	private void installOrUpdateBundle(String s, boolean startup) throws BundleException {
		// Check if bundle is already installed
		// Perform bundle update in this case
		for (Bundle b : context.getBundles()) {
			if (b.getLocation().endsWith(s)) {
				if (startup) // Don't update bundles on startup
					return;

				System.out.println("DirInstaller: Updating bundle [" + b.getSymbolicName() + "]");
				b.stop();
				b.update();
				return;
			}
		}
		// If the bundle is not installed, perform bundle install
		System.out.println("DirInstaller: Installing bundle [" + s + "]");
		context.installBundle("file:" + s);
	}

}
