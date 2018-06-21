package de.tu_darmstadt.stg.mubench;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.tu_darmstadt.stg.mubench.cli.*;
import edu.umd.cs.findbugs.*;
import edu.umd.cs.findbugs.config.UserPreferences;
import edu.umd.cs.findbugs.filter.Filter;
import edu.umd.cs.findbugs.filter.Matcher;

import javax.crypto.NullCipher;

public class FindBugsRunner {

	public static void main(String[] args) throws Exception {
		new MuBenchRunner()
				.withDetectOnlyStrategy(FindBugsRunner::runFindBugs)
				.withMineAndDetectStrategy(FindBugsRunner::runFindBugs)
				.run(args);
        NullCipher cipher = new NullCipher();
        cipher.doFinal(new byte[]{1,2,3,4});
    }

	private static DetectorOutput runFindBugs(DetectorArgs args, DetectorOutput.Builder output) throws IOException, InterruptedException {
        Project targetProject = buildTargetProject(args);
        BugReporter bugReporter = createBugReporter(targetProject);
        List<BugInstance> bugs = runFindbugs(targetProject, bugReporter);
		bugs.sort(Comparator.comparingInt(BugInstance::getBugRank));
        List<DetectorFinding> findings = convertToFindings(bugs);
        return output.withFindings(findings);
	}

    private static Project buildTargetProject(DetectorArgs args) throws IOException {
		Project project = new Project();
        for (String classFile : getClassFiles(args.getTargetClassPath())) {
            project.addFile(classFile);
        }
        for (String sourceDir : args.getTargetSrcPaths()) {
		    project.addSourceDir(sourceDir);
        }
		for (String dependency : args.getDependencyClassPath().getPaths()) {
			project.addAuxClasspathEntry(dependency);
		}
		return project;
	}

    private static List<String> getClassFiles(ClassPath dirs) throws IOException {
		List<String> allFiles = new ArrayList<>();
		for (String dir : dirs.getPaths()) {
            allFiles.addAll(getClassFiles(dir));
        }
        return allFiles;
	}

	private static List<String> getClassFiles(String dir) throws IOException {
		try (Stream<Path> paths = Files.walk(Paths.get(dir), Integer.MAX_VALUE)) {
			return paths.map(Path::toString).filter(path -> path.endsWith(".class")).collect(Collectors.toList());
		}
	}

    private static BugReporter createBugReporter(Project targetProject) throws IOException {
        String findbugsConfig = System.getProperty("findbugs.config");
        if (findbugsConfig == null) {
            throw new IllegalArgumentException("Missing Findbugs configuration. Run MUBench with '--java-options Dfindbugs.config=${CONFIG_FILE_PATH}'.");
        } else {
            File configFile = new File(findbugsConfig);
            if (!configFile.exists()) {
                throw new IllegalArgumentException(
                        "Illegal Findbugs configuration. The file (findbugs.config=)'" + findbugsConfig
                                + "' does not exist.");
            } else if (!configFile.isFile()) {
                throw new IllegalArgumentException(
                        "Illegal Findbugs configuration. The file (findbugs.config=)'" + findbugsConfig
                                + "' is not a file.");
            }
        }

        Matcher bugMatcher = new Filter(findbugsConfig);
        BugReporter bugReporter = new FilterBugReporter(new BugCollectionBugReporter(targetProject), bugMatcher, true);
        bugReporter.setPriorityThreshold(Priorities.LOW_PRIORITY);
        return bugReporter;
    }

    private static List<BugInstance> runFindbugs(Project targetProject, BugReporter bugReporter) throws IOException, InterruptedException {
        loadFindbugsPlugins();

        FindBugs2 findbugs = new FindBugs2();
        findbugs.setUserPreferences(UserPreferences.createDefaultUserPreferences());
        findbugs.setProject(targetProject);
        findbugs.setBugReporter(bugReporter);
        findbugs.setDetectorFactoryCollection(DetectorFactoryCollection.instance());
        findbugs.execute();
        BugCollection bugCollection = findbugs.getBugReporter().getBugCollection();
        if (bugCollection != null) {
            return new ArrayList<>(bugCollection.getCollection());
        } else {
            return new ArrayList<>();
        }
    }

    private static void loadFindbugsPlugins() throws IOException {
        try {
            // We shop Findbugs plugins as jar files within the /plugins directory in the runner jar. Unfortunately,
            // we cannot directly load them from within the runner jar, since Java cannot open URL connections to files
            // within a jar within a jar. Therefore, we extract the plugin jars into a temporary directory and load them
            // from there.
            Path tmpDirectory = Files.createTempDirectory("mubench-findbugs-");
            CodeSource src = FindBugsRunner.class.getProtectionDomain().getCodeSource();
            if (src != null) {
                Path jar = Paths.get(src.getLocation().getPath());
                FileSystem jarFS = FileSystems.newFileSystem(jar, null);
                Path pluginsPath = jarFS.getPath("plugins");
                try (DirectoryStream<Path> plugins = Files.newDirectoryStream(pluginsPath)) {
                    for (Path plugin : plugins) {
                        String pluginName = plugin.getFileName().toString();
                        Path pluginTargetPath = tmpDirectory.resolve(pluginName);
                        Files.copy(plugin, pluginTargetPath);
                        Plugin.addCustomPlugin(pluginTargetPath.toUri());
                    }
                }
            }
        } catch (PluginException e) {
            e.printStackTrace();
        }
    }

    private static List<DetectorFinding> convertToFindings(List<BugInstance> bugs) {
        MuBenchMethodFormatConverter muBenchConverter = new MuBenchMethodFormatConverter();
        List<DetectorFinding> findings = new ArrayList<>();
        for (BugInstance bug : bugs) {
            MethodAnnotation primaryMethod = bug.getPrimaryMethod();
            if (primaryMethod == null) {
                continue;
            }
            String methodName = primaryMethod.getMethodName();
            if (methodName.contains ("<clinit>")){
                methodName = "<init>";
            }
            String methodSig = primaryMethod.getMethodSignature();
            String extractedType = muBenchConverter.convert(methodSig);
            String srcPath = bug.getPrimarySourceLineAnnotation().getSourcePath();

            DetectorFinding finding;
            if(extractedType != null){
                finding = new DetectorFinding(srcPath, methodName + "(" +extractedType + ")");
            }else{
                finding = new DetectorFinding(srcPath, methodSig);
            }
            finding.put("rank", String.valueOf(bug.getBugRank()));
            finding.put("desc", bug.getMessage());
            finding.put("type", bug.getType());
            finding.put("startline", String.valueOf(bug.getPrimarySourceLineAnnotation().getStartLine()));

            findings.add(finding);
        }
        return findings;
    }
}