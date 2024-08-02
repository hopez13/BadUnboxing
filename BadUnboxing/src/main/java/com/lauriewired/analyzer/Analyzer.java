package com.lauriewired.analyzer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jadx.api.JadxDecompiler;

public class Analyzer implements Runnable {
    private String apkFilePath;
    private String resultFilePath;
    private String apkFileName;
    private SimpleLogger logger;

    public Analyzer(String apkFilePath, String outputRootPath) {
        this.apkFilePath = apkFilePath;
        this.apkFileName = new File(apkFilePath).getName().replaceAll(".apk$", "");
        String logPath = new File(outputRootPath + "/logs/" + apkFileName + ".txt").toString();
        this.logger = new SimpleLogger(logPath);
        this.resultFilePath =
                new File(outputRootPath + "/results/" + apkFileName + ".txt").toString();
    }

    @Override
    public void run() {
        AnalysisResult result = analyzeApk();

        try {
            Files.writeString(Path.of(resultFilePath), result.getFileRepresentation(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println(
                    "An error occurred while writing results to disk: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private List<String> isPacked(String apkFilePath, JadxDecompiler jadx) {
        List<String> packedClasses = new ArrayList<>();
        try {
            // Extract and parse AndroidManifest.xml
            JadxUtils jadxUtils = new JadxUtils(logger);
            Set<String> manifestClasses = jadxUtils.getManifestClasses(apkFilePath, jadx);

            // Get classes from dex files
            Set<String> dexClasses = jadxUtils.getDexClasses(apkFilePath, jadx);

            // Check if there are any classes in the manifest that are not in the dex files
            for (String className : manifestClasses) {
                if (!dexClasses.contains(className)) {
                    packedClasses.add(className);
                }
            }
        } catch (Exception e) {
            logger.error("Error checking if APK is packed");
            e.printStackTrace();
        }
        return packedClasses; // Return the list of packed classes
    }

    public AnalysisResult analyzeApk() {
        JadxUtils jadxUtils = new JadxUtils(logger);
        JadxDecompiler jadx = jadxUtils.loadJadx(apkFilePath);
        String packageName = jadx.getRoot().getAppPackage();
        if (packageName == null) {
            packageName = apkFileName;
        }
        
        logger.log("Loading APK:" + packageName);
        AnalysisResult result = new AnalysisResult(packageName);

        // sets the applicationSubclassPackageName field, must be run before dcl/reflection detection
        JadxUtils.findApplicationSubclass(jadx, logger, result);

        List<String> packedClasses = isPacked(apkFilePath, jadx);
        if (packedClasses.isEmpty()) {
            logger.log("APK is not packed");
            // return;
        }

        logger.log("APK is packed");

        // Missing classes which were found in AndroidManifest.xml
        logger.log("[BEGIN_LIST] Missing classes in dex");
        for (String className : packedClasses) {
            logger.log(className);
        }
        logger.log("[END_LIST] Missing classes in dex");

        DynamicDexLoaderDetection dclDetect = new DynamicDexLoaderDetection(logger, result);
        List<String> dclPackageNames = dclDetect.getDclPackages(jadx);
        if (dclPackageNames.isEmpty()) {
            logger.log("Packer Type:Native");
            result.packer = AnalysisResult.PackerType.NATIVE;
            // return;
        } else {
            logger.log("Packer Type:Java");
            result.packer = AnalysisResult.PackerType.JAVA;

            logger.log("[BEGIN_LIST] Package names using DCL");
            for (String detail : dclPackageNames) {
                logger.log(detail);
            }
            logger.log("[END_LIST] Package names using DCL");
            result.dclPackageNames = dclPackageNames;
        }

        UnpackerGenerator gen = new UnpackerGenerator(logger, result);
        ApkAnalysisDetails apkAnalysisDetails = gen.generateJava(jadx, apkFilePath);
        if (apkAnalysisDetails.getBaseDir() == null) {
            logger.error("Error generating Java unpacker code.");
        }

        List<String> reflectivePackageNames = ReflectionRemover.getReflectivePackages(jadx, logger, result);
        if (!reflectivePackageNames.isEmpty()) {
            logger.log("[BEGIN_LIST] Package names using reflection");
            for (String detail : reflectivePackageNames) {
                logger.log(detail);
            }
            logger.log("[END_LIST] Package names using reflection");
            result.reflectivePackageNames = reflectivePackageNames;
        }

        return result;
    }
}
