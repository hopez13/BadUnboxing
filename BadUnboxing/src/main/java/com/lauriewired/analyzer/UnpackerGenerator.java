package com.lauriewired.analyzer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

public class UnpackerGenerator {
    private String apkPath;
    private File baseDir;
    private int importsRecognized;
    private final Set<String> existingNames = new HashSet<>();
    private SimpleLogger logger;
    private AnalysisResult result;

    private static final Set<String> standardPackages =
            new HashSet<>(Arrays.asList("android", "com.android", "dalvik", "java", "javax",
                    "junit", "org.apache", "org.json", "org.w3c.dom", "org.xml.sax"));

    private final Set<String> androidOnlyImports =
            new HashSet<>(Arrays.asList("android", "com.android", "dalvik", "com.xiaomi"));

    public UnpackerGenerator(SimpleLogger logger, AnalysisResult result) {
        this.logger = logger;
        this.result = result;
    }

    public ApkAnalysisDetails generateJava(JadxDecompiler jadx, String apkFilePath) {
        // Calculating how well BadUnboxing processed this sample
        int recognizedImports = 0;
        String fullQualifiedClassName = "";

        // Packing stubs abuse Application subclass for unpacking code
        // Make that our starting point since code runs first upon instantiation
        JavaClass applicationClass = JadxUtils.findApplicationSubclass(jadx, logger, result);

        if (applicationClass != null) {
            try {
                Set<JavaClass> referencedClasses = new HashSet<>();
                findReferencedClasses(applicationClass, referencedClasses,
                        jadx); // Need all classes referenced by code

                // Rename the methods and fields first since we'll have to reload the code before
                // renaming args and vars
                for (JavaClass currentClass : referencedClasses) {
                    IdentifierRenamer.renameMethodsAndFields(currentClass, jadx, existingNames);
                }

                fullQualifiedClassName = generateUnpackerJava(
                        applicationClass, referencedClasses, apkFilePath, jadx);
            } catch (Exception e) {
                logger.error("Error generating Unpacker.java" + e);
            }
        } else {
            logger.log("No Application subclass found in the APK");
        }

        return (new ApkAnalysisDetails(baseDir, fullQualifiedClassName, recognizedImports));
    }

    private String generateUnpackerJava(JavaClass applicationClass,
            Set<JavaClass> referencedClasses, String apkFilePath, JadxDecompiler jadx) {
        StringBuilder javaCode = new StringBuilder();

        // Get the APK file
        File apkFile = new File(apkFilePath);
        apkPath = apkFilePath;

        // Determine the class name based on the APK file name
        String apkName = apkFile.getName();
        int dotIndex = apkName.lastIndexOf('.');
        String baseName = (dotIndex == -1) ? apkName : apkName.substring(0, dotIndex);
        String className =
                "Unpacker_" + (baseName.length() > 10 ? baseName.substring(0, 10) : baseName);
        baseDir = new File(apkFile.getParent(), className + "_BadUnboxing");

        // Extract the package name
        String fullyQualifiedName = applicationClass.getFullName();
        String packageName = fullyQualifiedName.substring(0, fullyQualifiedName.lastIndexOf('.'));

        // Initialize an ArrayList to store referenced class names
        ArrayList<String> referencedClassNames = new ArrayList<>();

        // Process the main application subclass (entrypoint)
        String appClassCode = processApplicationSubclass(applicationClass, className, jadx);
        javaCode.append(appClassCode).append("\n");

        // Process referenced classes
        for (JavaClass refClass : referencedClasses) {
            if (refClass != applicationClass) {
                insertNewClass(javaCode, refClass, jadx);
                referencedClassNames.add(refClass.getName());
            }
        }

        // Do final cleanups on full code that is now completely added
        finalProcessing(javaCode, className, packageName);

        /*
        try {
            outputClassesToFiles(apkFile, className, javaCode, referencedClassNames);
        } catch (IOException e) {
            logger.error("Error writing unpacker code to files");
            e.printStackTrace();
        }
        */

        return packageName + "." + className;
    }

    private void outputClassesToFiles(File apkFile, String className, StringBuilder javaCode,
            ArrayList<String> referencedClassNames) throws IOException {
        // Get the directory of the APK file and create a base directory that includes 'src'
        File outputDir = new File(baseDir, "src");

        // Create the base directory if it does not exist, including all necessary parent
        // directories
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Create settings.json in the base directory for the new unpacker project
        createSettingsJson(baseDir);

        // Regex pattern to capture content based on "package" declaration
        Pattern packagePattern = Pattern.compile(
                "(package\\s+([\\w\\.]+);).*?(?=package\\s+[\\w\\.]+;|$)", Pattern.DOTALL);
        Matcher packageMatcher = packagePattern.matcher(javaCode.toString());

        while (packageMatcher.find()) {
            String packageBlock = packageMatcher.group(0);
            String fullPackageName = packageMatcher.group(2);
            String packagePath = fullPackageName.replace(
                    '.', File.separatorChar); // Convert package name to directory path

            // Create the full path for the package inside the 'src' directory
            File packageDir = new File(outputDir, packagePath);
            if (!packageDir.exists()) {
                packageDir.mkdirs(); // Ensure the package directory structure is created
            }

            // Extract the class name from the current block of code
            Pattern classPattern = Pattern.compile(
                    "\\s+class\\s+(?!Application\\b|Context\\b)(\\w+)\\s+\\{"); // Simple class name
                                                                                // extraction
                                                                                // pattern
            Matcher classMatcher = classPattern.matcher(packageBlock);
            String fileName =
                    "UnknownClass.java"; // Default file name in case no class name is found
            if (classMatcher.find()) {
                fileName = classMatcher.group(1)
                        + ".java"; // Use the found class name for the file name
            }

            // Write to a new Java file for each package block using the class name
            File javaFile = new File(packageDir, fileName);
            try (FileWriter writer = new FileWriter(javaFile)) {
                writer.write(packageBlock);
                logger.log("Generated " + fileName + " at " + javaFile.getAbsolutePath());
            }
        }
    }

    private void createSettingsJson(File baseDir) throws IOException {
        File settingsFile = new File(baseDir, ".vscode/settings.json");

        // Ensure the .vscode directory exists
        if (!settingsFile.getParentFile().exists()) {
            settingsFile.getParentFile().mkdirs();
        }

        // Content of the settings.json file
        String settingsContent = "{\n"
                + "    \"java.project.sourcePaths\": [\"src\"],\n"
                + "    \"java.project.outputPath\": \"bin\",\n"
                + "    \"java.project.referencedLibraries\": [\n"
                + "        \"lib/**/*.jar\"\n"
                + "    ]\n"
                + "}";

        // Write the settings content to the settings.json file
        try (FileWriter writer = new FileWriter(settingsFile)) {
            writer.write(settingsContent);
            logger.log("Created settings.json at " + settingsFile.getAbsolutePath());
        }
    }

    private void finalProcessing(StringBuilder javaCode, String className, String packageName) {
        commentPackageName(javaCode);
        makeMethodsStatic(javaCode);
        makeFieldsStatic(javaCode);
        removeKeywordThis(javaCode);
        CodeReplacerUtils util = new CodeReplacerUtils(logger);
        util.processContextMethods(javaCode, className, packageName);
        util.processApplicationMethods(javaCode);
        commentAndroidSpecificImports(javaCode);
        ReflectionRemover reflectionRemover = new ReflectionRemover(logger, result);
        reflectionRemover.removeReflection(javaCode);
    }

    private void commentPackageName(StringBuilder javaCode) {
        // Pattern to match the package name declaration
        Pattern packagePattern = Pattern.compile("(?m)^package\\s+.*?;");
        Matcher matcher = packagePattern.matcher(javaCode);

        // Buffer to hold the modified code
        StringBuffer modifiedCode = new StringBuffer();

        // Comment out the package name declaration
        if (matcher.find()) {
            String packageStatement = matcher.group();
            String commentedPackageStatement = "// " + packageStatement;
            matcher.appendReplacement(modifiedCode, commentedPackageStatement);
        }
        matcher.appendTail(modifiedCode);

        // Replace the original code with the modified code
        javaCode.setLength(0);
        javaCode.append(modifiedCode);
    }

    private void commentAndroidSpecificImports(StringBuilder javaCode) {
        // Pattern to match import statements
        Pattern importPattern = Pattern.compile("(?m)^import\\s+.*?;");
        Matcher importMatcher = importPattern.matcher(javaCode);

        // Buffer to hold the modified code
        StringBuffer modifiedCode = new StringBuffer();

        while (importMatcher.find()) {
            String importStatement = importMatcher.group();
            // Extract the fully qualified class name from the import statement
            String className =
                    importStatement.replaceFirst("import\\s+", "").replaceFirst(";", "").trim();

            // Check if the class name starts with any of the specified prefixes
            boolean isAndroidImport = false;
            for (String prefix : androidOnlyImports) {
                if (className.startsWith(prefix)) {
                    isAndroidImport = true;
                    break;
                }
            }

            // If it is an Android-specific import, comment it out
            if (isAndroidImport) {
                importStatement = "// " + importStatement;
            }

            // Append the modified or unmodified import statement to the buffer
            importMatcher.appendReplacement(modifiedCode, importStatement);
        }
        importMatcher.appendTail(modifiedCode);

        // Replace the original code with the modified code
        javaCode.setLength(0);
        javaCode.append(modifiedCode);
    }

    private String processApplicationSubclass(
            JavaClass applicationClass, String className, JadxDecompiler jadx) {
        String appClassCode =
                IdentifierRenamer.renameArgsAndVars(applicationClass, jadx, existingNames)
                        .toString();
        appClassCode = appClassCode.replace(applicationClass.getName(), className);
        appClassCode = appClassCode.replaceAll("extends Application", "");
        appClassCode = appClassCode.replaceAll("@Override // android.app.Application", "");
        appClassCode = appClassCode.replaceAll("@Override // android.content.ContextWrapper", "");
        appClassCode = appClassCode.replaceAll("super\\.attachBaseContext\\(\\w+\\);",
                "//super.attachBaseContext(context); // BadUnboxing: Remove superclass reference");
        appClassCode = appClassCode.replaceAll("super\\.onCreate\\(\\);",
                "//super.onCreate(); // BadUnboxing: Remove superclass reference");
        appClassCode = replaceAttachBaseContextWithMain(appClassCode);

        // Extract imports from the application subclass
        StringBuilder imports = new StringBuilder();
        Matcher importMatcher = Pattern.compile("(?m)^import\\s+.*?;").matcher(appClassCode);
        while (importMatcher.find()) {
            imports.append(importMatcher.group()).append("\n");
        }

        // Fix keywords for imports inside application subclass
        appClassCode = processKeyWordsBasedOnImports(imports, appClassCode);

        return appClassCode;
    }

    private String replaceAttachBaseContextWithMain(String appClassCode) {
        // Pattern to find the attachBaseContext method signature and extract the parameter name
        Pattern pattern = Pattern.compile("(public|protected|private|\\s*)\\s*void\\s*"
                                          + "attachBaseContext\\(Context\\s+(\\w+)\\)");
        Matcher matcher = pattern.matcher(appClassCode);

        // Variable to store the Context parameter name
        String contextParamName = null;

        // Find and replace the method signature
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            contextParamName = matcher.group(2); // Capture the parameter name
            matcher.appendReplacement(sb, "public static void main(String[] args)");
        }
        matcher.appendTail(sb);
        appClassCode = sb.toString();

        // If a parameter name was found, replace all its occurrences in the method body
        if (contextParamName != null) {
            // Assuming the body of the method is properly isolated if needed for multiple methods
            // Replace all occurrences of the Context parameter with "new Context()" (assuming this
            // makes sense in your context)
            appClassCode = appClassCode.replaceAll(
                    "\\b" + Pattern.quote(contextParamName) + "\\b", "new Context()");
        }

        return appClassCode;
    }

    private void makeFieldsStatic(StringBuilder javaCode) {
        // Pattern to match field declarations that start with "field_"
        // This pattern looks for optional access modifiers, optional "final" keyword,
        // any valid Java type (primitive or object), optional array brackets, and field names
        // starting with "field_" It supports fields with or without initial access modifiers.
        Pattern fieldPattern = Pattern.compile(
                "(?m)^\\s*(public\\s+|protected\\s+|private\\s+)?(static\\s+)?(final\\s+)?([\\w\\["
                + "\\]\\<\\>]+\\s+)(field_\\w+\\s*)(=\\s*[^;]+)?;");

        Matcher matcher = fieldPattern.matcher(javaCode);
        StringBuffer modifiedCode = new StringBuffer();

        while (matcher.find()) {
            String accessModifier =
                    matcher.group(1) == null ? "" : matcher.group(1); // Capture access modifiers
            String isStatic = matcher.group(2); // Capture the 'static' keyword if it exists
            String finalModifier = matcher.group(3) == null
                    ? ""
                    : matcher.group(3); // Capture the 'final' keyword if it exists
            String type = matcher.group(4); // Capture the type
            String fieldName = matcher.group(5); // Capture the field name
            String initializer = matcher.group(6) == null
                    ? ""
                    : matcher.group(6); // Capture the initializer if present

            // Construct the replacement string
            String replacement;
            if (isStatic == null) {
                // If 'static' keyword is missing, add it
                replacement = accessModifier + "static " + finalModifier + type + fieldName
                        + initializer + ";";
            } else {
                // If 'static' keyword is already there, keep the declaration as it is
                replacement = matcher.group(0);
            }

            // Append the replacement to the buffer
            matcher.appendReplacement(modifiedCode, replacement);
        }
        matcher.appendTail(modifiedCode);

        // Replace the original code with the modified code
        javaCode.setLength(0);
        javaCode.append(modifiedCode);
    }

    private void makeMethodsStatic(StringBuilder javaCode) {
        // Pattern to match lines starting with whitespace followed by public, protected, or
        // private, followed by a single whitespace, and not followed by "static"
        Pattern methodPattern =
                Pattern.compile("(?m)^\\s*(public|protected|private)\\s+(?!static|class)");

        Matcher matcher = methodPattern.matcher(javaCode);

        // Buffer to hold the modified code
        StringBuffer modifiedCode = new StringBuffer();

        while (matcher.find()) {
            String methodSignature = matcher.group();
            // Modify the line to include "static" after the keyword
            String modifiedMethod =
                    methodSignature.replaceFirst("(public|protected|private)", "$1 static");
            matcher.appendReplacement(modifiedCode, modifiedMethod);
        }
        matcher.appendTail(modifiedCode);

        // Replace the original code with the modified code
        javaCode.setLength(0);
        javaCode.append(modifiedCode);
    }

    private void removeKeywordThis(StringBuilder javaCode) {
        // Pattern to match all occurrences of "this."
        Pattern thisPattern = Pattern.compile("\\bthis\\.");
        Matcher matcher = thisPattern.matcher(javaCode);
        StringBuffer modifiedCode = new StringBuffer();

        // Replace all occurrences with an empty string
        while (matcher.find()) {
            matcher.appendReplacement(modifiedCode, "");
        }
        matcher.appendTail(modifiedCode);

        // Replace the original code with the modified code
        javaCode.setLength(0);
        javaCode.append(modifiedCode);
    }

    private void insertNewClass(
            StringBuilder javaCode, JavaClass newCodeClass, JadxDecompiler jadx) {
        String newClassCode =
                IdentifierRenamer.renameArgsAndVars(newCodeClass, jadx, existingNames).toString();

        // Remove the package line and store it
        /*
        String packageLine = "";
        Matcher packageMatcher = Pattern.compile("(?m)^package\\s+.*?;").matcher(newClassCode);
        if (packageMatcher.find()) {
            packageLine = packageMatcher.group();
            newClassCode = newClassCode.replaceFirst("(?m)^package\\s+.*?;", "");
        }*/
        // TODO keep the package name so we can find it when separating classes

        // Process class imports and update the newClassCode by removing imports
        StringBuilder imports = processClassImports(javaCode, newClassCode);

        // TODO removing import moving due to placing files in separate java files
        /*
        newClassCode = newClassCode.replaceAll("(?m)^import\\s+.*?;", ""); // Remove imports from
        newClassCode

        // Insert new imports below the package line
        int packageLineEnd = javaCode.indexOf(";");
        if (packageLineEnd != -1) {
            javaCode.insert(packageLineEnd + 1, "\n\n" + imports.toString());
        } else {
            javaCode.insert(0, packageLine + "\n\n" + imports.toString() + "\n");
        }*/

        newClassCode = processKeyWordsBasedOnImports(imports, newClassCode);

        // Append the new class code without the package line and imports
        javaCode.append(newClassCode).append("\n");
    }

    /*
     * For performance, only search for methods and replace them if their parent class
     *  has been imported
     */
    private String processKeyWordsBasedOnImports(StringBuilder imports, String newClassCode) {
        // Extract each import statement
        Pattern importPattern = Pattern.compile("import\\s+([\\w\\.]+);");
        Matcher matcher = importPattern.matcher(imports);

        // For each import statement, check if it is supported and call the corresponding method
        while (matcher.find()) {
            String importClass = matcher.group(1);
            String prefix = importClass.split("\\.")[0];

            CodeReplacerUtils util = new CodeReplacerUtils(logger);

            if (androidOnlyImports.contains(prefix)) {
                switch (importClass) {
                    case "dalvik.system.DexClassLoader":
                        logger.log("Processing methods from dalvik.system.DexClassLoader import");
                        newClassCode = util.processDexClassLoaderMethods(newClassCode);
                        break;
                    case "android.os.Build":
                        logger.log("Processing methods from android.os.Build import");
                        newClassCode = util.processBuildMethods(newClassCode);
                        break;
                    case "android.content.pm.ApplicationInfo":
                        logger.log("Processing methods from android.content.pm.ApplicationInfo "
                                   + "import");
                        newClassCode = util.processApplicationInfoMethods(
                                newClassCode, apkPath);
                        break;
                    case "android.util.ArrayMap":
                        logger.log("Processing methods from import android.util.ArrayMap import");
                        newClassCode =
                                util.processArrayMapMethods(newClassCode, imports);
                        break;
                    case "android.app.Application":
                    case "android.content.Context":
                        // Ignore these because we will process them on all code no matter what
                        // Adding them as part of the switch anyway because they are technically
                        // supported
                        break;
                    default:
                        logger.error("Unknown android import: " + importClass);
                }
            }
        }

        return newClassCode;
    }

    private StringBuilder processClassImports(StringBuilder javaCode, String newClassCode) {
        // Extract imports from existing code
        Set<String> existingImports = new HashSet<>();
        Matcher importMatcher = Pattern.compile("(?m)^import\\s+.*?;").matcher(javaCode);
        while (importMatcher.find()) {
            existingImports.add(importMatcher.group());
        }

        StringBuilder imports = new StringBuilder();
        importMatcher = Pattern.compile("(?m)^import\\s+.*?;").matcher(newClassCode);
        while (importMatcher.find()) {
            String importStatement = importMatcher.group();
            if (!existingImports.contains(importStatement)) {
                imports.append(importStatement).append("\n");
                existingImports.add(importStatement);
            }
        }

        return imports;
    }

    private void findReferencedClasses(
            JavaClass javaClass, Set<JavaClass> referencedClasses, JadxDecompiler jadx) {
        // Add the current class to the referenced set if not already present
        if (!referencedClasses.add(javaClass)) {
            // Class already processed
            return;
        }

        String packageName = javaClass.getPackage();
        String classCode = javaClass.getCode();

        // Iterate through all classes in the decompiler
        for (JavaClass currentClass : jadx.getClasses()) {
            if (packageName.equals(currentClass.getPackage()) && !javaClass.equals(currentClass)
                    && !currentClass.getName().equals("R")) {
                if (classCode.contains(currentClass.getName())) {
                    referencedClasses.add(currentClass);
                    logger.log("Adding class " + currentClass.getName() + " to referenced classes");
                    findReferencedClasses(currentClass, referencedClasses, jadx);
                }
            }
        }
    }

    private boolean isCustomClass(String typeName) {
        for (String standardPackage : standardPackages) {
            if (typeName.startsWith(standardPackage)) {
                return false;
            }
        }
        return true;
    }
}