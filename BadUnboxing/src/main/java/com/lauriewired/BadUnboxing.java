package com.lauriewired;

import com.lauriewired.analyzer.Analyzer;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class BadUnboxing {
    private static final int MAX_THREADS = 20;

    private static List<String> findApkFiles(File folder) {
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".apk"));
        return Arrays.stream(files).map(File::getAbsolutePath).collect(Collectors.toList());
    }

    private static void printProgressBar(int completed, int total) {
        int barLength = 50;
        int progress = (int) ((double) completed / total * barLength);
        StringBuilder progressBar = new StringBuilder("[");
        for (int i = 0; i < barLength; i++) {
            if (i < progress) {
                progressBar.append("=");
            } else {
                progressBar.append(" ");
            }
        }
        progressBar.append("] ")
                .append(completed)
                .append("/")
                .append(total)
                .append(" (")
                .append(String.format("%.2f", (double) completed / total * 100))
                .append("%)");

        // Print progress bar
        System.out.print("\r" + progressBar.toString());
    }

    private static boolean endsWithAnySuffix(String str, List<String> suffixes) {
        return suffixes.stream().anyMatch(str::endsWith);
    }

    public static void main(String[] args) {
        if (args.length != 2 && args.length != 3) {
            System.err.println("usage: java -jar BadUnboxing.jar /path/to/apks /output/path [/path/to/blacklist]");
            System.exit(1);
        }
        String apkFilePath = args[0];
        String outputContainer = args[1];
        String blacklistPath = args[2];

        if (!new File(apkFilePath).isDirectory()) {
            System.err.println(
                    "the first argument must be a valid directory, containing apk files");
            System.exit(1);
        }

        List<String> apkFiles = findApkFiles(new File(apkFilePath));
        if (apkFiles.isEmpty()) {
            System.err.println("no apk files found in given directory");
            System.exit(1);
        }

        var blacklistFile = new File(blacklistPath);
        if (!blacklistFile.isFile()) {
            System.err.println("no blacklist file found at " + blacklistPath);
            System.exit(1);
        }
        try {
            List<String> blacklist =  Files.readAllLines(blacklistFile.toPath()).stream().map(line -> line.substring(0, line.length() - 3) + "apk").toList();
            System.out.println(blacklist.size() + " blacklist size");
            apkFiles = apkFiles.stream().filter(apk -> !endsWithAnySuffix(apk, blacklist)).toList();
            System.out.println(blacklist.get(0));
            System.out.println(apkFiles.get(0));
        } catch (Exception e) {
            e.printStackTrace();
        }



        int totalTasks = apkFiles.size();
        System.out.println("Found " + totalTasks + " apk files");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm");
        String timestamp = LocalDateTime.now().format(formatter);

        String outputRootPath =
                new File(outputContainer + "/BadUnboxing_results_" + timestamp + "/").toString();
        new File(outputRootPath).mkdirs();
        new File(outputRootPath + "/results/").mkdirs();
        new File(outputRootPath + "/logs/").mkdirs();

        ExecutorService executorService = Executors.newFixedThreadPool(MAX_THREADS);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger completedTasks = new AtomicInteger(0);

        for (String apkPath : apkFiles) {
            Future<?> future = executorService.submit(() -> {
                Analyzer analyzer = new Analyzer(apkPath, outputRootPath);
                analyzer.run();
                completedTasks.incrementAndGet();
            });
            futures.add(future);
        }

        for (Future<?> future : futures) {
            try {
                future.get(10, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                future.cancel(true);
                System.out.println("Task exceeded " + 10 + " minutes and was terminated.");
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }


        executorService.shutdown();

        /*
            Reflection varsa -> reflection=YES
            Reflection yoksa -> reflection=NO ; dcl=NO

            Dynamic code loading'in varlığını nasıl tespit edebiliriz?
            1. AndroidManifest'te tanımlı olan ancak classes.dex'te yer almayan class'ların varlığı,
           DCL'ye kesin kanıttır.
            2. DynamicDexLoaderDetection'da listelenen reflection keywordlerinden herhangi birinin
           yokluğu, DCL'nin olmadığına kesin kanıttır.

            1. maddenin kod hali: (isPacked().notEmpty() && reflection) -> dcl=YES

            Mümkünse, reflection ve DCL'nin meydana geldiği classların/packageların isimlerini de
           al. Böylece reflection'ın ve/veya DCL'nin app tarafında mı yoksa kütüphane tarafında mı
           olduğunu tespit etmek mümkün olabilir


            OUTPUT STRUCTURE
            <output_path>

            BadUnboxing_results_<timestamp>/
            |--- combined_table.tsv
            |--- logs/
                 |--- apkname1.txt
                 |--- apkname2.txt
                 ...
            |--- results/
                 |--- apkname1.txt
                 |--- apkname2.txt
                 ...

        */
    }
}
