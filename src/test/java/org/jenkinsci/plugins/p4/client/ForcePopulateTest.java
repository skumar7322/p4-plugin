package org.jenkinsci.plugins.p4.client;

import org.jenkinsci.plugins.p4.DefaultEnvironment;
import org.jenkinsci.plugins.p4.SampleServerExtension;
import org.jenkinsci.plugins.p4.changes.P4ChangeRef;
import org.jenkinsci.plugins.p4.populate.AutoCleanImpl;
import org.jenkinsci.plugins.p4.populate.ForceCleanImpl;
import org.jenkinsci.plugins.p4.populate.ParallelSync;
import org.jenkinsci.plugins.p4.populate.Populate;
import org.jenkinsci.plugins.p4.workspace.ManualWorkspaceImpl;
import org.jenkinsci.plugins.p4.workspace.WorkspaceSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for P4JENKINS-184.
 * <p>
 * A populate-style sync uses the '-p' (server bypass) flag so the server
 * have-table is not updated. Previously the '-f' (force) flag was suppressed
 * whenever '-p' was active, so if the have-table already believed the files
 * were synced (e.g. on a forwarding read-only replica) the server silently
 * bypassed content transfer: the console reported files 'added' while the
 * workspace was left empty on disk.
 * <p>
 * The fix combines '-p' with '-f' so archive content is always re-fetched.
 */
@WithJenkins
class ForcePopulateTest extends DefaultEnvironment {

	private static final String P4ROOT = "tmp-ForcePopulateTest-p4root";

	private static final int FILE_COUNT = 5;

	private JenkinsRule jenkins;

	@RegisterExtension
	private final SampleServerExtension p4d = new SampleServerExtension(P4ROOT, R24_1_r15);

	@BeforeEach
	void beforeEach(JenkinsRule rule) throws Exception {
		jenkins = rule;
		createCredentials("jenkins", "jenkins", p4d.getRshPort(), CREDENTIAL);
	}

	@Issue("P4JENKINS-184")
	@Test
	void testForcePopulateRewritesEmptyWorkspace() throws Exception {
		checkForcePopulate("force.ws", "//depot/forcePopulate/serial/", null);
	}

	@Issue("P4JENKINS-184")
	@Test
	void testForcePopulateParallelRewritesEmptyWorkspace() throws Exception {
		ParallelSync parallel = new ParallelSync(true, null, "4", "1", "1024");
		checkForcePopulate("forceParallel.ws", "//depot/forcePopulate/parallel/", parallel);
	}

	private void checkForcePopulate(String client, String depotPath, ParallelSync parallel) throws Exception {
		// Submit a set of files to the depot
		long change = 0;
		for (int i = 1; i <= FILE_COUNT; i++) {
			String submitted = submitFile(jenkins, depotPath + "file" + i + ".txt", "content " + i);
			change = Long.parseLong(submitted);
		}

		String view = depotPath + "... //" + client + "/...";
		WorkspaceSpec spec = new WorkspaceSpec(view, null);
		ManualWorkspaceImpl workspace = new ManualWorkspaceImpl("none", true, client, spec, false);

		File wsRoot = new File("target/" + client).getAbsoluteFile();
		workspace.setRootPath(wsRoot.toString());

		P4ChangeRef ref = new P4ChangeRef(change);

		try (ClientHelper p4 = new ClientHelper(jenkins.getInstance(), CREDENTIAL, null, workspace)) {
			// Normal sync populates the workspace and updates the have-table so the
			// server now believes every file is already synced.
			Populate seed = new AutoCleanImpl(true, true, false, false, false, "", null);
			p4.syncFiles(ref, seed);
			assertEquals(FILE_COUNT, countFiles(wsRoot), "seed sync should populate the workspace");

			// Simulate the empty workspace on a read-only replica.
			deleteFiles(wsRoot);
			assertEquals(0, countFiles(wsRoot), "workspace should be empty before force populate");

			// Force populate: '-p' server bypass combined with '-f' force. Without the
			// fix the server treats the files as 'already synced' and leaves the
			// workspace empty; with the fix the archive content is re-fetched.
			Populate populate = new ForceCleanImpl(false, false, "", parallel);
			p4.syncFiles(ref, populate);

			// AC-1 / AC-4: every reported file is actually written to disk.
			assertEquals(FILE_COUNT, countFiles(wsRoot), "force populate should re-write all files to disk");
		}
	}

	private static int countFiles(File dir) {
		int count = 0;
		File[] children = dir.listFiles();
		if (children == null) {
			return 0;
		}
		for (File child : children) {
			if (child.isDirectory()) {
				count += countFiles(child);
			} else {
				count++;
			}
		}
		return count;
	}

	private static void deleteFiles(File dir) {
		File[] children = dir.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			if (child.isDirectory()) {
				deleteFiles(child);
			} else {
				assertTrue(child.delete(), "unable to delete " + child);
			}
		}
	}
}
