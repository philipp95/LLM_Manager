package com.llmmanager.server.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Local subprocess fixture: no network, credentials, or provider CLI. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path marker = Path.of(args[1]);
        Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
        if (List.of(args).contains("--version")) return;
        if (mode.equals("tree")) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ProcessFixture.class.getName(), "hang", marker + ".child").inheritIO().start();
        }
        if (mode.equals("normal") || mode.equals("result-hang")) {
            System.out.println("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"},{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{}}],\"usage\":{\"output_tokens\":1}}}");
            System.err.println("-----BEGIN PRIVATE KEY-----");
            System.err.println("SYNTHETIC-KEY-MATERIAL");
            System.err.println("-----END PRIVATE KEY-----");
            System.out.println("{\"type\":\"result\",\"is_error\":false,\"result\":\"Authorization: Bearer syntheticCredential123\"}");
            if (mode.equals("normal")) return;
        }
        if (mode.equals("flood")) {
            for (int i = 0; i < 20000; i++) {
                System.out.println("stdout " + i);
                System.err.println("stderr " + i);
            }
            return;
        }
        if (mode.equals("exit")) return;
        Thread.sleep(120000);
    }
}
