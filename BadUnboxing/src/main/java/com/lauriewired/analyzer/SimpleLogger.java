package com.lauriewired.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class SimpleLogger {
    private String logPath;

    public SimpleLogger(String logPath) {
        this.logPath = logPath;
    }

    public void log(String msg) {
        try {
            //System.out.println(msg);
            Files.writeString(Path.of(logPath), msg + "\n", StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void error(String msg) {
        log("ERROR: " + msg);
    }
}
