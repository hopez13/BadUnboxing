package com.lauriewired.analyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

public class DynamicDexLoaderDetection  {
    private static final Set<String> dynamicDexLoadingKeywords = new HashSet<>(Arrays.asList(
        "DexClassLoader", "PathClassLoader", "InMemoryDexClassLoader", "BaseDexClassLoader", "loadDex", "OpenMemory"
    ));

    private SimpleLogger logger;
    private AnalysisResult result;

    public DynamicDexLoaderDetection(SimpleLogger logger, AnalysisResult result) {
        this.logger = logger;
        this.result = result;
    }

    public List<String> getDclPackages(JadxDecompiler jadx) {
        HashSet<String> packageNames = new HashSet<>();
        for (JavaClass cls : jadx.getClasses()) {
            String classCode = cls.getCode();
            for (String keyword : dynamicDexLoadingKeywords) {
                if (classCode.contains(keyword)) {
                    String detail = String.format("Found dcl keyword '%s' in class '%s'", keyword, cls.getFullName());
                    logger.log(detail);
                    if (cls.getPackage().startsWith(result.packageName) || cls.getPackage().startsWith(result.applicationSubclassPackageName)) {
                        result.dclInApp = true;
                    }
                    result.usesDcl = true;
                    packageNames.add(cls.getPackage());
                }
            }
        }
        return packageNames.stream().sorted().toList();
    }

    public static boolean hasNativeDexLoading() {
        // TODO

        // Save the jadx output directory and find the native libs (files ending in .so)
        
        return false;
    }
}
