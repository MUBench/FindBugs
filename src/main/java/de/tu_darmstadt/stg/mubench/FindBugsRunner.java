package de.tu_darmstadt.stg.mubench;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.tu_darmstadt.stg.mubench.cli.CodePath;
import de.tu_darmstadt.stg.mubench.cli.DetectorArgs;
import de.tu_darmstadt.stg.mubench.cli.DetectorFinding;
import de.tu_darmstadt.stg.mubench.cli.DetectorOutput;
import de.tu_darmstadt.stg.mubench.cli.MuBenchRunner;
import edu.umd.cs.findbugs.BugCollectionBugReporter;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.FindBugs2;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.Project;
import edu.umd.cs.findbugs.config.UserPreferences;

public class FindBugsRunner extends MuBenchRunner {

	public static void main(String[] args) throws Exception {
		new FindBugsRunner().run(args);
	}

	@Override
	protected void detectOnly(DetectorArgs args, DetectorOutput output) throws Exception {
		runFindBugs(args, output);
	}

	@Override
	protected void mineAndDetect(DetectorArgs args, DetectorOutput output) throws Exception {
		runFindBugs(args, output);
	}

	private void runFindBugs(DetectorArgs args, DetectorOutput output)
			throws FileNotFoundException, IOException, InterruptedException {
		FindBugs2 findbugs = new FindBugs2();
		Project targetProject = buildProject(args.getTargetPath(), args.getDependencyClassPath());
		BugReporter bugReporter = new BugCollectionBugReporter(targetProject);
		bugReporter.setPriorityThreshold(Priorities.LOW_PRIORITY);
		findbugs.setUserPreferences(UserPreferences.createDefaultUserPreferences());
		findbugs.setProject(targetProject);
		findbugs.setBugReporter(bugReporter);
		findbugs.setDetectorFactoryCollection(DetectorFactoryCollection.instance());
		findbugs.execute();
		for (BugInstance bug : findbugs.getBugReporter().getBugCollection()) {
			MethodAnnotation primaryMethod = bug.getPrimaryMethod();
			if (primaryMethod == null) {
				continue;
			}
			File sourceFile = new File(bug.getPrimarySourceLineAnnotation().getSourcePath());
			DetectorFinding finding = output.add(sourceFile.getAbsolutePath(), primaryMethod.getMethodName());
			finding.put("rank", String.valueOf(bug.getBugRank()));
			finding.put("desc", bug.getMessage());
			finding.put("type", bug.getType());
			finding.put("startline", String.valueOf(bug.getPrimarySourceLineAnnotation().getStartLine()));
		}
	}

	private List<File> findSourceFiles(String dir) {
		return getFiles(dir, ".java");
	}

	private List<File> findClassFiles(String dir) {
		return getFiles(dir, ".class");
	}

	private Project buildProject(CodePath path, String[] classPath) {
		Project project = new Project();
		List<File> files = findClassFiles(path.classPath);
		files.addAll(findSourceFiles(path.srcPath));
		for (File file : files) {
			project.addFile(file.getAbsolutePath());
		}
		for (String dependency : classPath) {
			project.addAuxClasspathEntry(dependency);
		}
		return project;
	}

	private List<File> getFiles(String dir, String suffix) {
		File directory = new File(dir);
		List<File> files = new ArrayList<>();

		// get all the files from a directory
		File[] fList = directory.listFiles();
		for (File file : fList) {
			if (file.isFile() && file.getName().contains(suffix)) {
				files.add(file);
			} else if (file.isDirectory()) {
				files.addAll(getFiles(file.getAbsolutePath(), suffix));
			}
		}
		return files;
	}

}