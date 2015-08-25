package com.hanhuy.gradle.discovery;

import com.android.builder.model.AndroidProject;
import com.android.builder.model.PackagingOptions;
import org.gradle.tooling.model.GradleProject;

/**
 * @author pfnguyen
 */
public interface GradleBuildModel {
    RepositoryListModel getRepositories();
    AndroidDiscoveryModel getDiscovery();
    AndroidProject getAndroidProject();
    PackagingOptions getPackagingOptions();
    GradleProject getGradleProject();
}
