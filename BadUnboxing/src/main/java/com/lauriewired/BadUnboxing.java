package com.lauriewired;

import com.lauriewired.analyzer.ApkAnalysisDetails;
import com.lauriewired.analyzer.DynamicDexLoaderDetection;
import com.lauriewired.analyzer.JadxUtils;
import com.lauriewired.analyzer.UnpackerGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jadx.api.JadxDecompiler;

public class BadUnboxing {
    private static final Logger logger = LoggerFactory.getLogger(BadUnboxing.class);

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: java -jar BadUnboxing.jar /path/to/apk");
            System.exit(1);
        }
        String apkFilePath = args[0];
        analyzeApk(apkFilePath);
    }

    private static List<String> isPacked(String apkFilePath, JadxDecompiler jadx) {
        List<String> packedClasses = new ArrayList<>();
        try {
            // Extract and parse AndroidManifest.xml
            Set<String> manifestClasses = JadxUtils.getManifestClasses(apkFilePath, jadx);

            // Get classes from dex files
            Set<String> dexClasses = JadxUtils.getDexClasses(apkFilePath, jadx);

            // Check if there are any classes in the manifest that are not in the dex files
            for (String className : manifestClasses) {
                if (!dexClasses.contains(className)) {
                    logger.info("Class {} found in manifest but not in dex files", className);
                    packedClasses.add(className);
                }
            }
        } catch (Exception e) {
            logger.error("Error checking if APK is packed", e);
        }
        return packedClasses; // Return the list of packed classes
    }

    public static void analyzeApk(String apkFilePath) {
        logger.info("Loading APK");
        JadxDecompiler jadx = JadxUtils.loadJadx(apkFilePath);

        List<String> packedClasses = isPacked(apkFilePath, jadx);
        if (packedClasses.isEmpty()) {
            logger.info("APK is not packed");
            return;
        }

        logger.info("APK is packed");

        // Missing classes which were found in AndroidManifest.xml
        logger.info("[BEGIN_LIST] Missing classes");
        for (String className : packedClasses) {
            logger.info(className);
        }
        logger.info("[END_LIST] Missing classes");

        List<String> dexLoadingDetails = DynamicDexLoaderDetection.getJavaDexLoadingDetails(jadx);
        if (dexLoadingDetails.isEmpty()) {
            logger.info("Packer Type:Native");
            return;
        }

        logger.info("Packer Type:Java");
        logger.info("[BEGIN_LIST] Code loader details");
        for (String detail : dexLoadingDetails) {
            logger.info(detail);
        }
        logger.info("[END_LIST] Code loader details");

        ApkAnalysisDetails apkAnalysisDetails = UnpackerGenerator.generateJava(jadx, apkFilePath);
        if (apkAnalysisDetails.getBaseDir() == null) {
            logger.error("Error generating Java unpacker code.");
        }
    }
}
