package io.github.stevdrey.prrevieworchestrator.adapters.github;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Disposable local commit history. Git only reads and writes within the fixture directory. */
final class SandboxRepository {
    private static final String SOURCE = "Example.java";
    private static final String ORIGINAL = "class Example { int value() { return -1; } }\n";
    private static final String FIXED = "class Example { int value() { return 0; } }\n";
    private final Path workTree;
    private final Path config;
    final String openedSha;
    final String fixedSha;
    final String followUpSha;

    SandboxRepository(Path directory) throws IOException, InterruptedException {
        workTree = Files.createDirectory(directory.resolve("repo"));
        config = Files.createFile(directory.resolve("gitconfig"));
        git("init", "--quiet", "--template=", "--object-format=sha1", "--initial-branch=fixture");
        openedSha = commit(ORIGINAL, "Open fixture PR");
        fixedSha = commit(FIXED, "Fix the fixture value");
        followUpSha = commit(FIXED + "// Follow-up\n", "Change the fixture tree again");
    }

    boolean changed(String base, String head) throws IOException, InterruptedException {
        return !git("rev-parse", base + "^{tree}").equals(git("rev-parse", head + "^{tree}"));
    }

    boolean verifiesFix(String base, String head) throws IOException, InterruptedException {
        return git("show", base + ":" + SOURCE).equals(ORIGINAL.strip())
                && git("show", head + ":" + SOURCE).equals(FIXED.strip());
    }

    String blob(String commit) throws IOException, InterruptedException {
        return git("rev-parse", commit + ":" + SOURCE);
    }

    private String commit(String content, String message) throws IOException, InterruptedException {
        Files.writeString(workTree.resolve(SOURCE), content);
        git("add", "--", SOURCE);
        git("commit", "--quiet", "-m", message);
        return git("rev-parse", "HEAD");
    }

    private String git(String... args) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Sandbox", "-c", "user.email=sandbox@example.invalid",
                "-c", "core.autocrlf=false"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(workTree.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", config.toString());
        var process = builder.start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Sandbox Git command timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) throw new IOException("Sandbox Git " + args[0] + " failed: " + output);
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
