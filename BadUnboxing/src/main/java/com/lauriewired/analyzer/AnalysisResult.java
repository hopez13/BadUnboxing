package com.lauriewired.analyzer;

import java.util.ArrayList;
import java.util.List;

public class AnalysisResult {
    /*
            package_name=<package name>
            application_subclass_package_name=<package_name>
            packer_type=[JAVA|NATIVE|NONE] - default NONE
            reflection=[YES|NO] - default NO
            dcl=[YES|NO] - default NO
            reflection_in_app=[YES|NO] - default NO
            dcl_in_app=[YES|NO] - default NO

            -- optional fields (may be multiple or none)
            dcl_package_name=...
            dcl_package_name=...
            reflective_package_name=...
            reflective_package_name=...
            -- optional fields are not shown in table due to their unknown size
     */

    public enum PackerType { NONE, JAVA, NATIVE }

    public String packageName;
    public String applicationSubclassPackageName;
    public PackerType packer = PackerType.NONE;
    public boolean usesReflection = false;
    public boolean usesDcl = false;
    public boolean reflectionInApp = false;
    public boolean dclInApp = false;
    public List<String> dclPackageNames = new ArrayList<>();
    public List<String> reflectivePackageNames = new ArrayList<>();

    public AnalysisResult(String packageName) {
        this.packageName = packageName;
    }

    public String getTsvRowRepresentation() {
        StringBuilder sb = new StringBuilder();
        sb.append(packageName);
        sb.append("\t");

        sb.append(applicationSubclassPackageName);
        sb.append("\t");

        sb.append(packer.toString());
        sb.append("\t");

        sb.append(usesReflection ? "YES" : "NO");
        sb.append("\t");

        sb.append(usesDcl ? "YES" : "NO");
        sb.append("\t");

        sb.append(reflectionInApp ? "YES" : "NO");
        sb.append("\t");

        sb.append(dclInApp ? "YES" : "NO");
        sb.append("\n");

        return sb.toString();
    }

    public String getFileRepresentation() {
        StringBuilder sb = new StringBuilder();
        sb.append("package_name=");
        sb.append(packageName);
        sb.append("\n");

        sb.append("application_subclass_package_name=");
        sb.append(applicationSubclassPackageName);
        sb.append("\n");

        sb.append("packer_type=");
        sb.append(packer.toString());
        sb.append("\n");

        sb.append("reflection=");
        sb.append(usesReflection ? "YES" : "NO");
        sb.append("\n");

        sb.append("dcl=");
        sb.append(usesDcl ? "YES" : "NO");
        sb.append("\n");

        sb.append("reflection_in_app=");
        sb.append(reflectionInApp ? "YES" : "NO");
        sb.append("\n");

        sb.append("dcl_in_app=");
        sb.append(dclInApp ? "YES" : "NO");
        sb.append("\n");

        for (String ref : reflectivePackageNames) {
            sb.append("reflective_package_name=");
            sb.append(ref);
            sb.append("\n");
        }

        for (String dcl : dclPackageNames) {
            sb.append("dcl_package_name=");
            sb.append(dcl);
            sb.append("\n");
        }

        return sb.toString();
    }
}
